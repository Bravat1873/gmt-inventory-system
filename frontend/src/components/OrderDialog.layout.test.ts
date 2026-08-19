import { readFileSync } from 'node:fs'
import { resolve } from 'node:path'
import { expect, it } from 'vitest'

it('uses responsive order product panels without a forced wide scroller', () => {
  const styles = readFileSync(resolve(process.cwd(), 'src/styles.css'), 'utf8')
  expect(styles).toMatch(/\.order-line-panel\s*\{/)
  expect(styles).toMatch(/\.order-line-metrics\s*\{/)
  expect(styles).not.toMatch(/\.order-lines\s*\{[^}]*min-width:\s*1400px/)
})
