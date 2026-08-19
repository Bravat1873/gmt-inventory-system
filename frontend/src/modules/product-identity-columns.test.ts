import { expect, it } from 'vitest'
import { moduleDefinitions } from './module-config'

it('keeps product identity columns complete and ordered in product and inventory lists', () => {
  for (const key of ['product', 'inventory']) {
    const module = moduleDefinitions.find(item => item.key === key)!
    const start = module.columns.indexOf('产品编号')
    expect(module.columns.slice(start, start + 3)).toEqual(['产品编号', '客户料号', '型号'])
    expect(module.fields.slice(start, start + 3)).toEqual(['productCode', 'customerPartNumber', 'model'])
  }
})
