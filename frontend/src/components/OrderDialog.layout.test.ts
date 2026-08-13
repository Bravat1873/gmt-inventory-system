import { readFileSync } from 'node:fs'
import { resolve } from 'node:path'
import { expect, it } from 'vitest'

it('reserves enough width for the order inventory summary without covering the product name', () => {
  const styles = readFileSync(resolve(process.cwd(), 'src/styles.css'), 'utf8')
  const lineColumns = styles.match(/\.order-line-full\s*\{([^}]*)\}/s)?.[1] ?? ''

  expect(lineColumns).toMatch(/grid-template-columns:[^;]*72px\s+170px\s+minmax\(145px,1\.2fr\)/)
  expect(styles).toMatch(/\.order-lines\s*\{[^}]*min-width:\s*1400px/)
})
