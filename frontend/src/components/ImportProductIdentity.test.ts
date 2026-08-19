import { readFileSync } from 'node:fs'
import { resolve } from 'node:path'
import { expect, it } from 'vitest'

it('shows product code, customer part number and model in order import preview', () => {
  const source = readFileSync(resolve(process.cwd(), 'src/components/ImportPanel.vue'), 'utf8')
  expect(source).toMatch(/<th>产品编号<\/th><th>客户料号<\/th><th>型号<\/th>/)
  expect(source).toContain("value(row, 'model')")
})
