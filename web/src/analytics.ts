import Decimal from 'decimal.js'
import { LosslessNumber, parse } from 'lossless-json'

// Exact source tokens are never regenerated from display approximations.
export type ExactTotal = number | LosslessNumber
const DisplayDecimal = Decimal.clone({ precision: 40, rounding: Decimal.ROUND_HALF_EVEN })
const jsonNumber = /^-?(?:0|[1-9]\d*)(?:\.\d+)?(?:[eE][+-]?\d+)?$/
export function isExactTotal(value: unknown): value is ExactTotal {
  if (typeof value === 'number') return Number.isFinite(value)
  if (!(value instanceof LosslessNumber) || !jsonNumber.test(value.value)) return false
  const parsed = new DisplayDecimal(value.value)
  // Never confuse underflow of the decimal library's own exponent range with exact zero.
  return parsed.isFinite() && (!parsed.isZero() || !/[1-9]/.test(value.value.split(/[eE]/)[0]))
}
export const exactText = (value: ExactTotal) => value instanceof LosslessNumber ? value.value : String(value)
const decimal = (value: ExactTotal) => new DisplayDecimal(exactText(value))
export const exactNonnegative = (value: unknown): value is ExactTotal => isExactTotal(value) && decimal(value).gte(0)
export const exactPositive = (value: unknown): value is ExactTotal => isExactTotal(value) && decimal(value).gt(0)

/** Same JSON-number wire contract, with exact analytical tokens kept before binary64 conversion.
 * Other numeric fields remain display-only; integer metadata must convert exactly.
 * Conflicting duplicate keys, invalid JSON and non-number string totals are rejected.
 * Identical duplicate values follow lossless-json's explicit coalescing policy.
 */
export function parseAnalyticsJson(text: string): unknown {
  return parse(text, (key, value) => {
    if (!(value instanceof LosslessNumber)) return value
    if (['sum', 'numerator', 'denominator'].includes(key)) return value
    const converted = Number(value.value)
    if (converted === 0 && !decimal(value).isZero()) return NaN
    if (['count', 'sampleCount', 'deviceCount', 'revision'].includes(key)
      && (!Number.isSafeInteger(converted) || !decimal(value).eq(converted))) return NaN
    return converted
  })
}

/** Divide before converting: a large finite exact total can have a finite mean.
 * null means genuinely outside binary64 display range, including nonzero underflow.
 */
export function displayRatio(numerator: ExactTotal, denominator: ExactTotal): number | null {
  if (!isExactTotal(numerator) || !exactPositive(denominator)) return null
  const ratio = decimal(numerator).div(decimal(denominator)), number = ratio.toNumber()
  return Number.isFinite(number) && (number !== 0 || ratio.isZero()) ? number : null
}
