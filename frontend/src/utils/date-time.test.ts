import { describe, expect, it } from 'vitest'
import { formatDateTimeToSecond } from './date-time'

describe('formatDateTimeToSecond', () => {
  it('formats ISO date time to seconds without milliseconds', () => {
    expect(formatDateTimeToSecond('2026-08-14T16:39:51.288')).toBe('2026-08-14 16:39:51')
  })

  it('uses a dash for an empty value', () => {
    expect(formatDateTimeToSecond()).toBe('—')
  })
})