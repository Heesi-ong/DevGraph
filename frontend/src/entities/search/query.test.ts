import { describe, expect, it } from 'vitest'
import { isSearchable } from './query'

describe('isSearchable', () => {
  it('requires at least two characters after trimming', () => {
    expect(isSearchable('')).toBe(false)
    expect(isSearchable('a')).toBe(false)
    expect(isSearchable('  a  ')).toBe(false)
    expect(isSearchable('ab')).toBe(true)
    expect(isSearchable('가나')).toBe(true)
  })

  it('does not search while a Korean syllable is still being composed', () => {
    expect(isSearchable('ㅅㅅ')).toBe(false)
    expect(isSearchable('ㄱ ㄴ')).toBe(false)
    expect(isSearchable('ㅅ가')).toBe(true)
  })

  it('counts a character outside the BMP once', () => {
    expect(isSearchable('😀')).toBe(false)
    expect(isSearchable('😀😀')).toBe(true)
  })
})
