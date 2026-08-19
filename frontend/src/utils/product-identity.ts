export interface ProductIdentity {
  productCode?: string | null
  customerPartNumber?: string | null
  model?: string | null
}

export const identityValue = (value?: string | null) => String(value ?? '').trim() || '—'

export const productIdentityLines = (value: ProductIdentity) => [
  `产品编号：${identityValue(value.productCode)}`,
  `客户料号：${identityValue(value.customerPartNumber)}`,
  `型号：${identityValue(value.model)}`,
]

export const productIdentitySearchText = (value: ProductIdentity) =>
  [value.productCode, value.customerPartNumber, value.model].filter(Boolean).join(' ')
