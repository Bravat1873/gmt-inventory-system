export function formatDateTimeToSecond(value?: string): string {
  if (!value) return '—'
  return value.replace('T', ' ').slice(0, 19)
}