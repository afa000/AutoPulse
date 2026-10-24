package dev.autopulse.trip;

import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

/** Opt-in public adapter: points, generation and processing metadata share ONE snapshot. */
@Service
public class PublicOwnedHistory {
    private final TripQueries trips;
    private final OwnedVehicleHistory owned;
    private final OwnedHistoryCursor cursors;
    private final TransactionTemplate snapshot;
    private final org.springframework.jdbc.core.JdbcTemplate jdbc;
    public PublicOwnedHistory(TripQueries trips,OwnedVehicleHistory owned,OwnedHistoryCursor cursors,PlatformTransactionManager transactions,org.springframework.jdbc.core.JdbcTemplate jdbc) {
        this.jdbc=jdbc;
        this.trips=trips; this.owned=owned; this.cursors=cursors;
        snapshot=new TransactionTemplate(transactions);
        snapshot.setReadOnly(true); snapshot.setTimeout(10);
        snapshot.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
        snapshot.setIsolationLevel(TransactionDefinition.ISOLATION_REPEATABLE_READ);
    }
    public TripQueries.Result<HistoryQueries.Page> history(String publicVehicle,HistoryRequest query) {
        if (query.tripId()!=null || query.resolution().equals("raw")) throw TripQueries.invalid("Owned public history requires unfiltered aggregate resolution");
        return snapshot.execute(tx -> {
            var vehicle=trips.vehicle(publicVehicle); trips.gate(vehicle);
            var position=query.cursor()==null?null:cursors.decode(query.cursor(),vehicle,query);
            var internal=new HistoryRequest(query.from(),query.to(),query.limit(),null,null,query.resolution());
            var cursor=position==null?null:new OwnedVehicleHistory.Cursor(vehicle,query.from(),query.to(),query.resolution(),query.limit(),position.generation(),position.after());
            var page=owned.readInSnapshot(publicVehicle,internal,cursor);
            String next=page.nextCursor()==null?null:cursors.encode(vehicle,query,new OwnedHistoryCursor.Position(page.generation(),page.nextCursor().after()));
            String processing=trips.processing(vehicle);
            return new TripQueries.Result<>(new HistoryQueries.Page(query.from(),query.to(),query.resolution(),page.points(),next,Long.toString(page.generation()),
                    HistoryQueries.availability(jdbc,vehicle,query,processing)),processing);
        });
    }
}
