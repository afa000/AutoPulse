"""Versioned, single-owner SQLite storage for immutable device observations.

No network operation belongs inside this module or its database transactions.
Opening an existing invalid store never recreates it or clears its contents.
"""

import hashlib
import os
import shutil
import sqlite3
import threading
from contextlib import closing
from dataclasses import dataclass
from datetime import datetime, timezone
from pathlib import Path
from urllib.parse import urlsplit, urlunsplit

from simplejson import loads

from ..models.diagnostics import snapshot_from_json
from ..models.telemetry_json import sample_from_payload
from .capacity import CapacityPolicy

VERSION = 4
MAX_DATABASE_BYTES = 64 * 1024 * 1024
MAX_PAYLOAD_BYTES = 262144 - len(b'{"readings":[]}')
MAX_SEQUENCE = 9223372036854775807


class OutboxError(RuntimeError):
    """Storage cannot safely continue; preserve the database for inspection."""


class OwnershipError(OutboxError):
    pass


class IdentityConflict(OutboxError):
    pass


class CapacityError(OutboxError):
    pass


@dataclass(frozen=True)
class Association:
    device_id: str
    vehicle_id: str
    server_url: str

    def __post_init__(self):
        if any(type(value) is not str or not value.strip() for value in (self.device_id, self.vehicle_id)):
            raise ValueError("device and vehicle IDs must be nonempty strings")
        if type(self.server_url) is not str:
            raise ValueError("server URL must be a string")
        parsed = urlsplit(self.server_url)
        if (parsed.scheme not in ("http", "https") or not parsed.hostname or
                parsed.username is not None or parsed.password is not None or
                parsed.query or parsed.fragment):
            raise ValueError("server URL must be an HTTP(S) base without credentials, query or fragment")
        try:
            parsed.port  # Validate malformed or out-of-range ports.
        except ValueError as error:
            raise ValueError("invalid server URL port") from error
        object.__setattr__(self, "server_url", urlunsplit((parsed.scheme, parsed.netloc, parsed.path.rstrip("/"), "", "")))


@dataclass(frozen=True)
class StoredObservation:
    enqueue_id: int
    kind: str
    payload: bytes
    digest: str
    enqueued_at: str
    state: str
    attempts: int
    retry_at: str | None
    reason: str | None


def _unique(pairs):
    result = {}
    for key, value in pairs:
        if key in result:
            raise ValueError("duplicate JSON key")
        result[key] = value
    return result


def _validate(kind, payload, association):
    if type(payload) is not bytes or not payload or len(payload) > MAX_PAYLOAD_BYTES:
        raise ValueError("payload must be bounded nonempty UTF-8 JSON bytes")
    if kind == "telemetry":
        try:
            value = loads(payload.decode("utf-8"), use_decimal=True, allow_nan=False, object_pairs_hook=_unique)
            sample = sample_from_payload(value)
        except (ValueError, TypeError, KeyError, UnicodeError) as error:
            raise ValueError("invalid telemetry payload") from error
        allowed = {"deviceId", "vehicleId", "bootId", "recordedAt", "sequenceNumber", "source", "rpm", "speedKph", "coolantTempC", "engineLoadPct", "throttlePositionPct", "intakeTempC", "mafGps", "fuelLevelPct", "controlModuleVoltageV", "milOn"}
        if set(value) - allowed or sample.sequence_number > MAX_SEQUENCE:
            raise ValueError("invalid telemetry fields or sequence")
        identity = (sample.device_id, sample.vehicle_id, str(sample.boot_id), sample.sequence_number)
    elif kind == "diagnostic":
        sample = snapshot_from_json(payload)
        identity = (sample.device_id, sample.vehicle_id, str(sample.boot_id), sample.diagnostic_sequence)
    else:
        raise ValueError("unknown observation kind")
    if identity[:2] != (association.device_id, association.vehicle_id):
        raise ValueError("observation does not match outbox association")
    return identity


class _OwnerLock:
    def __init__(self, path):
        self.file = open(path, "a+b")
        try:
            if os.name == "nt":
                import msvcrt
                self.file.seek(0)
                msvcrt.locking(self.file.fileno(), msvcrt.LK_NBLCK, 1)
            else:
                import fcntl
                fcntl.flock(self.file.fileno(), fcntl.LOCK_EX | fcntl.LOCK_NB)
        except (OSError, BlockingIOError) as error:
            self.file.close()
            raise OwnershipError("outbox is already owned by another runner") from error

    def close(self):
        if not self.file.closed:
            try:
                if os.name == "nt":
                    import msvcrt
                    self.file.seek(0)
                    msvcrt.locking(self.file.fileno(), msvcrt.LK_UNLCK, 1)
                else:
                    import fcntl
                    fcntl.flock(self.file.fileno(), fcntl.LOCK_UN)
            finally:
                self.file.close()


class Outbox:
    """Own one file until close; each operation gets a thread-local connection."""

    def __init__(self, path, association: Association, *, policy=None, free_space=None):
        if not isinstance(association, Association):
            raise ValueError("typed association required")
        if policy is not None and not isinstance(policy, CapacityPolicy):
            raise ValueError("typed capacity policy required")
        self.path = Path(path).resolve()
        self.association = association
        self._requested_policy = policy
        self.policy = policy or CapacityPolicy()
        self._policy_verified = False
        self._free_space = free_space or (lambda path: shutil.disk_usage(path).free)
        self._lock = _OwnerLock(str(self.path) + ".lock")
        self._closed = False
        self._upload_lock = threading.Lock()
        self._next_upload = 0.0
        try:
            created = False
            try:
                with self.path.open("xb"):
                    created = True
            except FileExistsError:
                pass
            with closing(self._connect()) as db:
                if created:
                    self._initialize(db)
                else:
                    self._verify(db)
                    db.execute("UPDATE health SET collection_state='stopped' WHERE singleton=1 AND collection_state='collecting'")
                self._policy_verified = True
        except Exception:
            self._lock.close()
            raise

    def _connect(self):
        if self._closed:
            raise OutboxError("outbox is closed")
        db = None
        try:
            db = sqlite3.connect(self.path.as_uri() + "?mode=rw", uri=True, timeout=2, isolation_level=None)
            db.execute("PRAGMA busy_timeout=2000")
            db.execute("PRAGMA synchronous=FULL")
            page_size = db.execute("PRAGMA page_size").fetchone()[0]
            cap = self.policy.database_bytes if self._policy_verified else MAX_DATABASE_BYTES
            maximum = db.execute(f"PRAGMA max_page_count={cap // page_size}").fetchone()[0]
            if maximum * page_size > cap:
                raise OutboxError("outbox exceeds the database size limit; file preserved")
            return db
        except (sqlite3.Error, OutboxError) as error:
            if db is not None:
                db.close()
            if isinstance(error, OutboxError):
                raise
            raise OutboxError("unable to open outbox storage") from error

    def _initialize(self, db):
        try:
            if db.execute("PRAGMA journal_mode=DELETE").fetchone()[0].lower() != "delete":
                raise OutboxError("rollback journal mode unavailable; file preserved")
            db.executescript("""
                BEGIN IMMEDIATE;
                CREATE TABLE association (singleton INTEGER PRIMARY KEY CHECK(singleton=1), device_id TEXT NOT NULL, vehicle_id TEXT NOT NULL, server_url TEXT NOT NULL);
                CREATE TABLE outbox (
                    enqueue_id INTEGER PRIMARY KEY AUTOINCREMENT,
                    kind TEXT NOT NULL CHECK(kind IN ('telemetry','diagnostic')),
                    device_id TEXT NOT NULL, boot_id TEXT NOT NULL, sequence_number INTEGER NOT NULL,
                    wire_version INTEGER NOT NULL CHECK(wire_version=1),
                    payload BLOB NOT NULL, payload_bytes INTEGER NOT NULL, digest TEXT NOT NULL,
                    enqueued_at TEXT NOT NULL, state TEXT NOT NULL CHECK(state IN ('queued','waiting','blocked')),
                    attempts INTEGER NOT NULL DEFAULT 0, retry_at TEXT, reason TEXT,
                    UNIQUE(kind, device_id, boot_id, sequence_number)
                );
                CREATE TABLE health (singleton INTEGER PRIMARY KEY CHECK(singleton=1), collection_state TEXT NOT NULL, last_upload_at TEXT, pause_started_at TEXT, pause_ended_at TEXT, acknowledged_count INTEGER NOT NULL DEFAULT 0, discarded_count INTEGER NOT NULL DEFAULT 0);
                CREATE TABLE review_audit (singleton INTEGER PRIMARY KEY CHECK(singleton=1), release_count INTEGER NOT NULL DEFAULT 0, last_action TEXT, last_enqueue_id INTEGER, last_at TEXT);
                CREATE TABLE diagnostic_receipt (singleton INTEGER PRIMARY KEY CHECK(singleton=1), enqueue_id INTEGER, snapshot_id TEXT, result TEXT, received_at TEXT);
                CREATE TABLE capacity (singleton INTEGER PRIMARY KEY CHECK(singleton=1), payload_bytes INTEGER NOT NULL, rows INTEGER NOT NULL, oldest_seconds INTEGER NOT NULL, database_bytes INTEGER NOT NULL, control_reserve_bytes INTEGER NOT NULL, disk_free_bytes INTEGER NOT NULL);
                ALTER TABLE health ADD COLUMN pause_reason TEXT;
                ALTER TABLE health ADD COLUMN last_pause_seconds REAL;
                ALTER TABLE health ADD COLUMN pause_count INTEGER NOT NULL DEFAULT 0;
                INSERT INTO health(singleton, collection_state) VALUES(1, 'stopped');
                INSERT INTO review_audit(singleton) VALUES(1);
                INSERT INTO diagnostic_receipt(singleton) VALUES(1);
                PRAGMA user_version=4;
            """)
            db.execute("INSERT INTO association VALUES (1, ?, ?, ?)", (self.association.device_id, self.association.vehicle_id, self.association.server_url))
            db.execute("INSERT INTO capacity VALUES (1,?,?,?,?,?,?)", tuple(vars(self.policy).values()))
            db.execute("COMMIT")
        except sqlite3.Error as error:
            if db.in_transaction:
                db.execute("ROLLBACK")
            raise OutboxError("unable to initialize outbox; file preserved") from error

    def _verify(self, db):
        try:
            version = db.execute("PRAGMA user_version").fetchone()[0]
            if version not in (1, 2, 3, VERSION):
                raise OutboxError(f"unsupported outbox schema version {version}; file preserved")
            if db.execute("PRAGMA journal_mode").fetchone()[0].lower() != "delete":
                raise OutboxError("unexpected outbox journal mode; file preserved")
            if db.execute("PRAGMA quick_check").fetchone()[0] != "ok":
                raise OutboxError("outbox integrity check failed; file preserved")
            row = db.execute("SELECT device_id, vehicle_id, server_url FROM association WHERE singleton=1").fetchone()
            if row != (self.association.device_id, self.association.vehicle_id, self.association.server_url):
                raise OutboxError("outbox association mismatch; file preserved")
            # Ensure the expected tables are readable, even if a file was altered.
            db.execute("SELECT enqueue_id, kind, payload, payload_bytes, digest, enqueued_at, state, attempts, retry_at, reason FROM outbox LIMIT 0")
            if db.execute("SELECT collection_state FROM health WHERE singleton=1").fetchone() is None:
                raise OutboxError("outbox health record missing; file preserved")
            if version == 1:
                db.executescript("""BEGIN IMMEDIATE;
                    CREATE TABLE review_audit (singleton INTEGER PRIMARY KEY CHECK(singleton=1), release_count INTEGER NOT NULL DEFAULT 0, last_action TEXT, last_enqueue_id INTEGER, last_at TEXT);
                    INSERT INTO review_audit(singleton) VALUES(1);
                    PRAGMA user_version=2;
                    COMMIT;""")
                version = 2
            if db.execute("SELECT release_count FROM review_audit WHERE singleton=1").fetchone() is None:
                raise OutboxError("outbox review audit missing; file preserved")
            if version == 2:
                db.executescript("""BEGIN IMMEDIATE;
                    CREATE TABLE diagnostic_receipt (singleton INTEGER PRIMARY KEY CHECK(singleton=1), enqueue_id INTEGER, snapshot_id TEXT, result TEXT, received_at TEXT);
                    INSERT INTO diagnostic_receipt(singleton) VALUES(1);
                    PRAGMA user_version=3;
                    COMMIT;""")
                version = 3
            if db.execute("SELECT singleton FROM diagnostic_receipt WHERE singleton=1").fetchone() is None:
                raise OutboxError("diagnostic receipt record missing; file preserved")
            if version == 3:
                db.execute("BEGIN IMMEDIATE")
                db.execute("CREATE TABLE capacity (singleton INTEGER PRIMARY KEY CHECK(singleton=1), payload_bytes INTEGER NOT NULL, rows INTEGER NOT NULL, oldest_seconds INTEGER NOT NULL, database_bytes INTEGER NOT NULL, control_reserve_bytes INTEGER NOT NULL, disk_free_bytes INTEGER NOT NULL)")
                db.execute("ALTER TABLE health ADD COLUMN pause_reason TEXT")
                db.execute("ALTER TABLE health ADD COLUMN last_pause_seconds REAL")
                db.execute("ALTER TABLE health ADD COLUMN pause_count INTEGER NOT NULL DEFAULT 0")
                db.execute("UPDATE health SET collection_state='stopped',pause_started_at=NULL,pause_ended_at=NULL")
                db.execute("INSERT INTO capacity VALUES (1,?,?,?,?,?,?)", tuple(vars(self.policy).values()))
                db.execute("PRAGMA user_version=4")
                db.execute("COMMIT")
            values = db.execute("SELECT payload_bytes,rows,oldest_seconds,database_bytes,control_reserve_bytes,disk_free_bytes FROM capacity WHERE singleton=1").fetchone()
            if values is None:
                raise OutboxError("capacity settings missing; file preserved")
            try:
                persisted = CapacityPolicy(*values)
            except ValueError as error:
                raise OutboxError("invalid persisted capacity settings; file preserved") from error
            if self._requested_policy is not None and self._requested_policy != persisted:
                raise OutboxError("outbox capacity policy mismatch; file preserved")
            self.policy = persisted
            page_size = db.execute("PRAGMA page_size").fetchone()[0]
            if db.execute("PRAGMA page_count").fetchone()[0] * page_size > self.policy.database_bytes:
                raise OutboxError("outbox exceeds configured database limit; file preserved")
        except sqlite3.Error as error:
            raise OutboxError("invalid or corrupt outbox; file preserved") from error

    def enqueue(self, kind: str, payload: bytes, *, now=None):
        device, _, boot, sequence = _validate(kind, payload, self.association)
        timestamp = now if now is not None else datetime.now(timezone.utc)
        if not isinstance(timestamp, datetime) or timestamp.utcoffset() is None:
            raise ValueError("enqueue time must be timezone-aware")
        timestamp = timestamp.astimezone(timezone.utc).isoformat().replace("+00:00", "Z")
        digest = hashlib.sha256(payload).hexdigest()
        try:
            if self._disk_free() < self.policy.disk_free_bytes:
                raise CapacityError("outbox disk headroom reached; collection paused")
            with closing(self._connect()) as db:
                db.execute("BEGIN IMMEDIATE")
                row = db.execute("SELECT enqueue_id, payload FROM outbox WHERE kind=? AND device_id=? AND boot_id=? AND sequence_number=?", (kind, device, boot, sequence)).fetchone()
                if row:
                    if row[1] != payload:
                        raise IdentityConflict("same local identity has different content")
                    db.execute("COMMIT")
                    return row[0], False
                state = db.execute("SELECT collection_state FROM health WHERE singleton=1").fetchone()[0]
                if state in ("paused", "storage_failure"):
                    raise CapacityError("collection remains paused until capacity is rechecked")
                count, size = db.execute("SELECT COUNT(*),COALESCE(SUM(payload_bytes),0) FROM outbox").fetchone()
                if count >= self.policy.rows or size + len(payload) > self.policy.payload_bytes:
                    raise CapacityError("outbox logical capacity reached; collection paused")
                oldest = self._oldest(db)
                if oldest is not None and self._age(oldest, timestamp) >= self.policy.oldest_seconds:
                    raise CapacityError("outbox oldest pending age reached; collection paused")
                page_size = db.execute("PRAGMA page_size").fetchone()[0]
                data_pages = (self.policy.database_bytes - self.policy.control_reserve_bytes) // page_size
                allocated = db.execute("PRAGMA page_count").fetchone()[0]
                # Control writes may have grown the file above the data ceiling.
                # Reuse its freed pages without allowing further file growth there.
                db.execute(f"PRAGMA max_page_count={max(data_pages, allocated)}")
                cursor = db.execute("INSERT INTO outbox(kind,device_id,boot_id,sequence_number,wire_version,payload,payload_bytes,digest,enqueued_at,state) VALUES(?,?,?,?,1,?,?,?,?, 'queued')", (kind, device, boot, sequence, payload, len(payload), digest, timestamp))
                used_pages = db.execute("PRAGMA page_count").fetchone()[0] - db.execute("PRAGMA freelist_count").fetchone()[0]
                if used_pages > data_pages:
                    # Closing the uncommitted transaction rolls back this insert.
                    raise CapacityError("outbox active data page limit reached; collection paused")
                db.execute("COMMIT")
                return cursor.lastrowid, True
        except sqlite3.Error as error:
            raise OutboxError("outbox write failed; evidence was not declared collected") from error

    def _disk_free(self):
        try:
            value = self._free_space(self.path.parent)
            if type(value) is not int or value < 0:
                raise ValueError("invalid free disk reading")
            return value
        except (OSError, ValueError) as error:
            raise OutboxError("disk space check failed; collection cannot safely continue") from error

    @staticmethod
    def _oldest(db):
        row = db.execute("SELECT enqueued_at FROM outbox ORDER BY julianday(enqueued_at),enqueue_id LIMIT 1").fetchone()
        return row[0] if row else None

    @staticmethod
    def _age(oldest, now):
        try:
            a, b = datetime.fromisoformat(oldest), datetime.fromisoformat(now)
            if a.utcoffset() is None or b.utcoffset() is None:
                raise ValueError("missing offset")
            return max(0.0, (b - a).total_seconds())
        except (ValueError, TypeError, OverflowError) as error:
            raise OutboxError("invalid outbox time; file preserved") from error

    def collection_room(self, maximum_payload, *, now=None):
        """Update durable pause/resume state and reserve room before a source read."""
        if type(maximum_payload) is not int or maximum_payload < 1 or maximum_payload > MAX_PAYLOAD_BYTES:
            raise ValueError("invalid maximum payload reservation")
        current = now if now is not None else datetime.now(timezone.utc)
        if not isinstance(current, datetime) or current.utcoffset() is None:
            raise ValueError("capacity clock must be timezone-aware")
        stamp = current.astimezone(timezone.utc).isoformat().replace("+00:00", "Z")
        reserved = maximum_payload
        try:
            free = self._disk_free()
            with closing(self._connect()) as db:
                db.execute("BEGIN IMMEDIATE")
                count, size = db.execute("SELECT COUNT(*),COALESCE(SUM(payload_bytes),0) FROM outbox").fetchone()
                state, started = db.execute("SELECT collection_state,pause_started_at FROM health WHERE singleton=1").fetchone()
                oldest = self._oldest(db)
                age = self._age(oldest, stamp) if oldest else 0.0
                page_size = db.execute("PRAGMA page_size").fetchone()[0]
                pages = db.execute("PRAGMA page_count").fetchone()[0]
                free_pages = db.execute("PRAGMA freelist_count").fetchone()[0]
                data_limit = self.policy.database_bytes - self.policy.control_reserve_bytes
                used_bytes = (pages - free_pages) * page_size
                reason = None
                if free < self.policy.disk_free_bytes:
                    reason = "disk_headroom"
                elif age >= self.policy.oldest_seconds:
                    reason = "oldest_pending_age"
                elif count >= self.policy.rows or size + reserved > self.policy.payload_bytes:
                    reason = "logical_capacity"
                elif used_bytes + reserved + 2 * page_size > data_limit:
                    reason = "database_capacity"
                elif state in ("paused", "storage_failure") and (count * 4 > self.policy.rows * 3 or size * 4 > self.policy.payload_bytes * 3):
                    reason = "low_water"
                if reason:
                    if state not in ("paused", "storage_failure"):
                        db.execute("UPDATE health SET collection_state='paused',pause_reason=?,pause_started_at=?,pause_ended_at=NULL,pause_count=MIN(pause_count+1,9223372036854775807) WHERE singleton=1", (reason, stamp))
                    else:
                        db.execute("UPDATE health SET pause_reason=? WHERE singleton=1", (reason,))
                else:
                    duration = None
                    if state in ("paused", "storage_failure") and started:
                        # A backward clock makes the interval unknown, not zero lost measurements.
                        difference = (current.astimezone(timezone.utc) - datetime.fromisoformat(started)).total_seconds()
                        duration = difference if difference >= 0 else None
                    if state in ("paused", "storage_failure"):
                        db.execute("UPDATE health SET collection_state='collecting',pause_reason=NULL,pause_ended_at=?,last_pause_seconds=? WHERE singleton=1", (stamp, duration))
                    else:
                        db.execute("UPDATE health SET collection_state='collecting',pause_reason=NULL WHERE singleton=1")
                db.execute("COMMIT")
                return reason is None
        except sqlite3.Error as error:
            raise OutboxError("unable to check collection capacity") from error

    def stop_collection(self):
        try:
            with closing(self._connect()) as db:
                db.execute("UPDATE health SET collection_state='stopped' WHERE singleton=1 AND collection_state='collecting'")
        except sqlite3.Error as error:
            raise OutboxError("unable to record collection stop") from error

    def record_storage_failure(self):
        """Best effort only: storage failure itself may prevent this write."""
        stamp = datetime.now(timezone.utc).isoformat().replace("+00:00", "Z")
        try:
            with closing(self._connect()) as db:
                db.execute("""UPDATE health SET
                    pause_started_at=CASE WHEN collection_state IN ('paused','storage_failure') THEN COALESCE(pause_started_at,?) ELSE ? END,
                    pause_count=CASE WHEN collection_state IN ('paused','storage_failure') THEN pause_count ELSE MIN(pause_count+1,9223372036854775807) END,
                    collection_state='storage_failure',pause_reason='storage_failure',pause_ended_at=NULL WHERE singleton=1""", (stamp, stamp))
        except (sqlite3.Error, OutboxError) as error:
            raise OutboxError("could not persist storage failure") from error

    def pending(self, *, limit=100):
        if type(limit) is not int or not 1 <= limit <= 100:
            raise ValueError("limit must be 1..100")
        return self._read_rows("SELECT enqueue_id,kind,payload,payload_bytes,digest,enqueued_at,state,attempts,retry_at,reason FROM outbox ORDER BY enqueue_id LIMIT ?", (limit,))

    def blocked(self, *, after_id=0, limit=100):
        """Inspect/export blocked immutable payloads in bounded pages."""
        if type(after_id) is not int or after_id < 0 or type(limit) is not int or not 1 <= limit <= 100:
            raise ValueError("after_id must be nonnegative and limit must be 1..100")
        return self._read_rows("SELECT enqueue_id,kind,payload,payload_bytes,digest,enqueued_at,state,attempts,retry_at,reason FROM outbox WHERE state='blocked' AND enqueue_id>? ORDER BY enqueue_id LIMIT ?", (after_id, limit))

    def _read_rows(self, query, parameters):
        try:
            with closing(self._connect()) as db:
                rows = db.execute(query, parameters).fetchall()
            result = []
            for row in rows:
                ident, kind, payload, size, digest, at, state, attempts, retry, reason = row
                if type(payload) is not bytes or len(payload) != size or hashlib.sha256(payload).hexdigest() != digest:
                    raise OutboxError("outbox payload integrity check failed; file preserved")
                result.append(StoredObservation(ident, kind, payload, digest, at, state, attempts, retry, reason))
            return result
        except sqlite3.Error as error:
            raise OutboxError("outbox read failed; file preserved") from error

    def has_blocked(self):
        try:
            with closing(self._connect()) as db:
                return db.execute("SELECT 1 FROM outbox WHERE state='blocked' LIMIT 1").fetchone() is not None
        except sqlite3.Error as error:
            raise OutboxError("outbox review status unavailable") from error

    def status(self, *, now=None):
        """Bounded aggregate status, without exporting payloads."""
        try:
            with closing(self._connect()) as db:
                db.execute("BEGIN")
                return self._status_from_db(db, self.path, self._disk_free(), now=now, owner_active=True)
        except (sqlite3.Error, TypeError) as error:
            raise OutboxError("outbox status unavailable") from error

    @staticmethod
    def _status_from_db(db, path, free, *, now=None, owner_active=False):
        groups = db.execute("SELECT kind,state,COUNT(*),COALESCE(SUM(payload_bytes),0) FROM outbox GROUP BY kind,state ORDER BY kind,state").fetchall()
        head = db.execute("SELECT retry_at FROM outbox ORDER BY enqueue_id LIMIT 1").fetchone()
        oldest = Outbox._oldest(db)
        health = db.execute("SELECT collection_state,last_upload_at,acknowledged_count,discarded_count,pause_reason,pause_started_at,pause_ended_at,last_pause_seconds,pause_count FROM health WHERE singleton=1").fetchone()
        limits = db.execute("SELECT payload_bytes,rows,oldest_seconds,database_bytes,control_reserve_bytes,disk_free_bytes FROM capacity WHERE singleton=1").fetchone()
        blocked = db.execute("SELECT enqueue_id,reason FROM outbox WHERE state='blocked' ORDER BY enqueue_id LIMIT 1").fetchone()
        receipt = db.execute("SELECT snapshot_id,result,received_at FROM diagnostic_receipt WHERE singleton=1").fetchone()
        current = now if now is not None else datetime.now(timezone.utc)
        if not isinstance(current, datetime) or current.utcoffset() is None:
            raise ValueError("status time must be timezone-aware")
        stamp = current.astimezone(timezone.utc).isoformat().replace("+00:00", "Z")
        age = Outbox._age(oldest, stamp) if oldest else None
        warning = bool(oldest and datetime.fromisoformat(oldest) > current)
        state = health[0] if owner_active or health[0] != "collecting" else "stopped"
        def file_size(file):
            try:
                return file.stat().st_size
            except FileNotFoundError:
                return 0
        return {"groups": [{"kind": kind, "state": state, "count": count, "bytes": size} for kind, state, count, size in groups],
                "oldest_enqueued_at": oldest, "oldest_pending_age_seconds": age, "clock_warning": warning,
                "head_retry_at": head[0] if head else None,
                "collection_state": state, "owner_active": owner_active, "pause_reason": health[4],
                "pause_started_at": health[5], "pause_ended_at": health[6], "last_pause_seconds": health[7], "pause_count": health[8],
                "delivery": "delivery unknown/offline", "last_upload_at": health[1],
                "acknowledged_count": health[2], "discarded_count": health[3],
                "capacity": dict(zip(("payload_bytes", "rows", "oldest_seconds", "database_bytes", "control_reserve_bytes", "disk_free_bytes"), limits)),
                "physical": {"database_bytes": file_size(path), "journal_bytes": file_size(Path(str(path) + "-journal")), "disk_free_bytes": free},
                "blocked": {"enqueue_id": blocked[0], "reason": blocked[1]} if blocked else None,
                "last_diagnostic_receipt": {"snapshotId": receipt[0], "result": receipt[1], "receivedAt": receipt[2]} if receipt[0] else None}

    @staticmethod
    def inspect_status(path, association):
        """Read a consistent snapshot without taking runner ownership or mutating storage."""
        if not isinstance(association, Association):
            raise ValueError("typed association required")
        try:
            resolved = Path(path).resolve()
            try:
                probe = _OwnerLock(str(resolved) + ".lock")
            except OwnershipError:
                owner_active = True
            else:
                probe.close()
                owner_active = False
            free = shutil.disk_usage(resolved.parent).free
            with closing(sqlite3.connect(resolved.as_uri() + "?mode=ro", uri=True, timeout=2, isolation_level=None)) as db:
                db.execute("PRAGMA query_only=ON")
                db.execute("BEGIN")
                if db.execute("PRAGMA user_version").fetchone()[0] != VERSION:
                    raise OutboxError("outbox version requires owner inspection or migration")
                actual = db.execute("SELECT device_id,vehicle_id,server_url FROM association WHERE singleton=1").fetchone()
                if actual != (association.device_id, association.vehicle_id, association.server_url):
                    raise OutboxError("outbox association mismatch")
                return Outbox._status_from_db(db, resolved, free, owner_active=owner_active)
        except (sqlite3.Error, TypeError, OSError) as error:
            raise OutboxError("outbox read-only status unavailable") from error

    def clamp_retry(self, row, deadline):
        try:
            with closing(self._connect()) as db:
                db.execute("BEGIN IMMEDIATE")
                changed = db.execute("UPDATE outbox SET retry_at=? WHERE enqueue_id=? AND state='waiting' AND retry_at=? AND digest=?", (deadline, row.enqueue_id, row.retry_at, row.digest)).rowcount
                if changed != 1:
                    raise OutboxError("retry head changed while clamping deadline")
                db.execute("COMMIT")
        except sqlite3.Error as error:
            raise OutboxError("unable to adjust retry deadline") from error

    def _change_delivery(self, rows, action, *, outcomes=None, retry_at=None, reason=None, now=None, receipt=None):
        """Guard request-to-row mapping and mutate all outcomes in one transaction."""
        if not rows:
            raise ValueError("nonempty submitted rows required")
        try:
            with closing(self._connect()) as db:
                db.execute("BEGIN IMMEDIATE")
                head = db.execute("SELECT enqueue_id FROM outbox ORDER BY enqueue_id LIMIT ?", (len(rows),)).fetchall()
                if [r[0] for r in head] != [r.enqueue_id for r in rows]:
                    raise OutboxError("submitted rows are no longer the queue head")
                for row in rows:
                    actual = db.execute("SELECT kind,payload,digest,state FROM outbox WHERE enqueue_id=?", (row.enqueue_id,)).fetchone()
                    if actual != (row.kind, row.payload, row.digest, row.state):
                        raise OutboxError("submitted row changed before settlement")
                if action == "settle":
                    if outcomes is None or len(outcomes) != len(rows):
                        raise ValueError("complete indexed outcomes required")
                    if receipt is not None:
                        if len(rows) != 1 or rows[0].kind != "diagnostic" or outcomes[0][0] not in ("accepted", "duplicate"):
                            raise ValueError("diagnostic receipt must match one settled row")
                        db.execute("UPDATE diagnostic_receipt SET enqueue_id=?,snapshot_id=?,result=?,received_at=? WHERE singleton=1", (rows[0].enqueue_id, receipt["snapshotId"], receipt["result"], receipt["receivedAt"]))
                    count = 0
                    for row, (status, detail) in zip(rows, outcomes):
                        if status in ("accepted", "duplicate"):
                            db.execute("DELETE FROM outbox WHERE enqueue_id=?", (row.enqueue_id,))
                            count += 1
                        elif status == "rejected" and detail:
                            db.execute("UPDATE outbox SET state='blocked',reason=?,retry_at=NULL WHERE enqueue_id=?", (detail, row.enqueue_id))
                        else:
                            raise ValueError("invalid settlement outcome")
                    if count:
                        db.execute("UPDATE health SET acknowledged_count=MIN(9223372036854775807,acknowledged_count+?),last_upload_at=? WHERE singleton=1", (count, now))
                elif action == "retry":
                    db.execute("UPDATE outbox SET state='waiting',attempts=MIN(attempts+1,32),retry_at=?,reason=? WHERE enqueue_id=?", (retry_at, reason, rows[0].enqueue_id))
                elif action == "block":
                    # A request-level rejection cannot identify the responsible item.
                    for row in rows:
                        db.execute("UPDATE outbox SET state='blocked',reason=?,retry_at=NULL WHERE enqueue_id=?", (reason, row.enqueue_id))
                else:
                    raise ValueError("unknown delivery action")
                db.execute("COMMIT")
        except sqlite3.Error as error:
            raise OutboxError("outbox delivery state update failed; evidence preserved") from error

    def review_action(self, enqueue_id, action, *, now=None):
        """Explicitly release or discard one blocked row, recording bounded audit."""
        if type(enqueue_id) is not int or enqueue_id < 1 or action not in ("release", "discard"):
            raise ValueError("blocked row ID and release or discard required")
        timestamp = now if now is not None else datetime.now(timezone.utc)
        if not isinstance(timestamp, datetime) or timestamp.utcoffset() is None:
            raise ValueError("review time must be timezone-aware")
        at = timestamp.astimezone(timezone.utc).isoformat().replace("+00:00", "Z")
        try:
            with closing(self._connect()) as db:
                db.execute("BEGIN IMMEDIATE")
                row = db.execute("SELECT state FROM outbox WHERE enqueue_id=?", (enqueue_id,)).fetchone()
                if row != ("blocked",):
                    raise OutboxError("review action requires a blocked row")
                if action == "release":
                    db.execute("UPDATE outbox SET state='queued',retry_at=NULL,reason=NULL WHERE enqueue_id=?", (enqueue_id,))
                    db.execute("UPDATE review_audit SET release_count=MIN(9223372036854775807,release_count+1),last_action=?,last_enqueue_id=?,last_at=? WHERE singleton=1", (action, enqueue_id, at))
                else:
                    db.execute("DELETE FROM outbox WHERE enqueue_id=?", (enqueue_id,))
                    db.execute("UPDATE health SET discarded_count=MIN(9223372036854775807,discarded_count+1) WHERE singleton=1")
                    db.execute("UPDATE review_audit SET last_action=?,last_enqueue_id=?,last_at=? WHERE singleton=1", (action, enqueue_id, at))
                db.execute("COMMIT")
        except sqlite3.Error as error:
            raise OutboxError("review action failed; evidence preserved") from error

    def close(self):
        if not self._closed:
            self._closed = True
            self._lock.close()

    def __enter__(self):
        return self

    def __exit__(self, *args):
        self.close()
