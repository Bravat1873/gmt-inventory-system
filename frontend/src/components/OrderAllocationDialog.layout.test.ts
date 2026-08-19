import { readFileSync } from 'node:fs'
import { resolve } from 'node:path'
import { expect, it } from 'vitest'

it('keeps every allocation product panel at its content height and scrolls the list', () => {
  const styles = readFileSync(resolve(process.cwd(), 'src/styles.css'), 'utf8')
  const listStyles = styles.match(/\.allocation-product-panels\s*\{([^}]*)\}/s)?.[1] ?? ''
  const panelStyles = styles.match(/\.allocation-product-panel\s*\{([^}]*)\}/s)?.[1] ?? ''

  expect(listStyles).toMatch(/overflow:\s*auto/)
  expect(panelStyles).toMatch(/flex:\s*0\s+0\s+auto/)
})
