// <input type="datetime-local">는 시간대 없는 "YYYY-MM-DDTHH:mm" 문자열을 쓴다. 서버는 ISO-8601 Instant를 쓴다.
export function isoToLocalInput(iso: string | null | undefined): string {
  if (!iso) return ''
  const d = new Date(iso)
  const pad = (n: number) => String(n).padStart(2, '0')
  return `${d.getFullYear()}-${pad(d.getMonth() + 1)}-${pad(d.getDate())}T${pad(d.getHours())}:${pad(d.getMinutes())}`
}

export function localInputToIso(local: string): string | undefined {
  return local ? new Date(local).toISOString() : undefined
}

export function formatDateTime(iso: string | null | undefined): string {
  return iso ? new Date(iso).toLocaleString('ko-KR') : ''
}
