import { readFileSync } from 'node:fs'
import { resolve } from 'node:path'
import { expect, it } from 'vitest'

it('wraps complete product identity values without ellipsis or clipping', () => {
  const styles = readFileSync(resolve(process.cwd(), 'src/styles.css'), 'utf8')
  const block = styles.match(/\.product-identity-display\s*\{([^}]*)\}/s)?.[1] ?? ''
  const value = styles.match(/\.product-identity-value\s*\{([^}]*)\}/s)?.[1] ?? ''

  expect(block).toContain('min-width:0')
  expect(value).toMatch(/overflow-wrap:\s*anywhere/)
  expect(value).toMatch(/white-space:\s*normal/)
  expect(value).not.toMatch(/text-overflow:\s*ellipsis/)
  expect(value).not.toMatch(/overflow:\s*hidden/)
})
