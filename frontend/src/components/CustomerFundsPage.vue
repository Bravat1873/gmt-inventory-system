<script setup lang="ts">
import { computed, onMounted, reactive, ref } from 'vue'
import type { UserRole } from '../api/auth'
import { downloadFundExport, fundSourceLabel, canReviewCustomerFunds, loadFundCustomers, loadFundOperations, reverseCustomerFund, type FundCustomer, type FundOperation, type FundPage } from '../api/customer-funds'
import { formatDateTimeToSecond } from '../utils/date-time'
import FundOperationDialog from './FundOperationDialog.vue'
import FundReviewDialog from './FundReviewDialog.vue'
import FuzzyPicker from './FuzzyPicker.vue'
import PurchaseFundsPanel from './PurchaseFundsPanel.vue'
const props=defineProps<{currentUserRole:UserRole}>()
const emit=defineEmits<{customer:[row:Record<string,unknown>];navigateCustomer:[name:string];message:[text:string,kind?:'success'|'error']}>()
const customers=ref<FundCustomer[]>([]),data=ref<FundPage>({items:[],total:0,page:1,pageSize:10}),tab=ref<'operations'|'ledger'|'purchases'>('operations'),loading=ref(false),error=ref(''),newOpen=ref(false)
const review=ref<{item:FundOperation;approved:boolean}>(),detail=ref<FundOperation>(),reversal=ref<FundOperation>(),reversalReason=ref(''),reversing=ref(false)
const filters=reactive({customerId:null as number|null,keyword:'',status:'',direction:'',fundSource:'',from:'',to:''})
const canReview=computed(()=>canReviewCustomerFunds(props.currentUserRole))
const detailReviewAction=computed(()=>detail.value?.status==='REJECTED'?'驳回':detail.value?.status==='REVERSED'?'原确认':'确认')
const options=computed(()=>customers.value.map(c=>({id:c.id,label:`${c.customerName} · ${c.customerCode}`})))
const money=(value?:number)=>Number(value??0).toLocaleString('zh-CN',{minimumFractionDigits:2,maximumFractionDigits:4})
const statuses:Record<string,string>={PENDING:'待确认',APPROVED:'已确认',REJECTED:'已驳回',REVERSED:'已冲正'}
const types:Record<string,string>={CUSTOMER_DEPOSIT:'客户打款',ORDER_RECEIPT:'订单抵扣',ORDER_REFUND:'订单抵扣退回',AFTER_SALES_REFUND:'售后退回余额',REVERSAL:'冲正'}
const summaryMetrics=[{key:'pendingReceipt',label:'待确认收款'},{key:'pendingRefund',label:'待确认退款'},{key:'confirmedReceipt',label:'已确认收款'},{key:'confirmedRefund',label:'已确认退款'}] as const
let generation=0
const exporting=ref(false)
function filterParams(){const params=new URLSearchParams();for(const [key,value] of Object.entries(filters))if(value!==null&&value!=='')params.set(key,String(value));return params}
async function exportFunds(){exporting.value=true;try{await downloadFundExport(filterParams(),tab.value==='ledger');emit('message','资金 Excel 已开始下载')}catch(cause){emit('message',cause instanceof Error?cause.message:'资金导出失败','error')}finally{exporting.value=false}}
async function load(page=1){
 const token=++generation;loading.value=true;error.value=''
 const params=filterParams();params.set('page',String(page))
 try{const result=await loadFundOperations(params,tab.value==='ledger');if(token===generation)data.value=result}catch(cause){if(token===generation){error.value=cause instanceof Error?cause.message:'读取资金记录失败';data.value={items:[],total:0,page:1,pageSize:10}}}finally{if(token===generation)loading.value=false}
}
async function reload(){try{customers.value=await loadFundCustomers();await load(data.value.page)}catch(cause){error.value=cause instanceof Error?cause.message:'读取客户失败'}}
function switchTab(next:'operations'|'ledger'|'purchases'){tab.value=next;filters.status='';if(next!=='purchases')load()}
async function changed(){newOpen.value=false;review.value=undefined;reversal.value=undefined;await reload()}
async function reverse(){if(!reversalReason.value.trim()){error.value='请填写冲正原因';return}reversing.value=true;try{await reverseCustomerFund(reversal.value!.ledgerId!,reversalReason.value.trim());emit('message','已冲正，客户余额已更新');await changed()}catch(cause){error.value=cause instanceof Error?cause.message:'冲正失败'}finally{reversing.value=false}}
onMounted(reload);defineExpose({reload})
</script>
<template>
 <section class="customer-funds-page">
  <header class="funds-page-heading"><div><h1>资金管理</h1><p>客户资金、采购付款及资金确认</p></div><div v-if="tab!=='purchases'" class="fund-heading-actions"><button class="secondary-action" data-test="export-funds" :disabled="loading||exporting" @click="exportFunds">{{ exporting?'正在导出…':'导出资金数据' }}</button><button class="primary-action" data-test="new-fund-operation" :disabled="loading" @click="newOpen=true">新增资金记录</button></div></header>
  <nav class="fund-tabs"><button :class="{active:tab==='operations'}" @click="switchTab('operations')">收付款登记</button><button :class="{active:tab==='ledger'}" @click="switchTab('ledger')">余额流水</button><button data-test="purchase-funds-tab" :class="{active:tab==='purchases'}" @click="switchTab('purchases')">采购付款</button></nav>
  <PurchaseFundsPanel v-if="tab==='purchases'" :current-user-role="currentUserRole" @message="(text,kind)=>emit('message',text,kind)" />
  <template v-else>
  <form class="funds-filters" @submit.prevent="load()">
   <label><span>客户</span><FuzzyPicker v-model="filters.customerId" :options="options" placeholder="全部客户，可搜索名称或编码" /></label>
   <label><span>资金来源</span><select data-test="fund-source-filter" v-model="filters.fundSource"><option value="">全部来源</option><option value="FOREX">外汇</option><option value="DOMESTIC">国内</option></select></label>
   <label><span>操作编号 / 客户</span><input v-model="filters.keyword" placeholder="输入关键词"></label>
   <label><span>状态</span><select v-model="filters.status"><option value="">全部状态</option><option v-if="tab==='operations'" value="PENDING">待确认</option><option value="APPROVED">已确认</option><option v-if="tab==='operations'" value="REJECTED">已驳回</option><option value="REVERSED">已冲正</option></select></label>
   <label><span>{{ tab==='operations'?'收付款类型':'变动方向' }}</span><select v-model="filters.direction"><option value="">全部</option><option value="IN">{{ tab==='operations'?'收款':'余额增加' }}</option><option value="OUT">{{ tab==='operations'?'退款':'余额扣减' }}</option></select></label>
   <label><span>操作日期从</span><input v-model="filters.from" type="date"></label><label><span>至</span><input v-model="filters.to" type="date"></label>
   <button class="secondary-action" :disabled="loading">查询</button>
  </form>
  <div v-if="tab==='operations' && data.summary" class="funds-summary">
   <div v-for="metric in summaryMetrics" :key="metric.key"><span>{{ metric.label }}</span><strong>¥ {{ money(data.summary[metric.key]) }}</strong></div>
  </div>
  <p class="field-hint">{{ tab==='operations'?'待确认记录不计入客户可用余额；汇总按当前筛选条件显示，已冲正记录不计入已确认金额。':'订单抵扣、售后退回及冲正在此记录余额变动，不重复统计为银行收付款。' }}</p>
  <p v-if="error" class="form-error" role="alert">{{ error }}</p>
  <div class="funds-table-scroll" :aria-busy="loading"><table>
   <thead><tr><th>操作编号</th><th>客户名称</th><th>资金来源</th><th v-if="tab==='ledger'">业务类型</th><th>{{ tab==='operations'?'收付款类型':'变动方向' }}</th><th>资金金额</th><th>操作时间</th><th>状态</th><th>人员</th><template v-if="tab==='operations'"><th>打款时间</th><th>资金确认</th></template><template v-else><th>变动前余额</th><th>变动后余额</th></template><th>操作</th></tr></thead>
   <tbody><tr v-for="item in data.items" :key="item.id">
    <td>{{ item.operationNo||'—' }}</td><td><button class="text-action" data-test="fund-customer-link" @click="emit('navigateCustomer',item.customerName)">{{ item.customerName }}</button><small>{{ item.customerCode }}</small></td>
    <td>{{ fundSourceLabel(item.fundSource) }}<small v-if="item.sourceNo">{{ item.sourceNo }}</small></td><td v-if="tab==='ledger'">{{ types[item.entryType??'']??item.entryType }}</td>
    <td>{{ item.direction==='OUT'?(tab==='operations'?'退款':'余额扣减'):(tab==='operations'?'收款':'余额增加') }}</td><td class="fund-amount" :class="{danger:item.direction==='OUT'}">¥ {{ money(item.amount) }}</td>
    <td>{{ formatDateTimeToSecond(item.submittedAt) }}</td><td>{{ statuses[item.status]??item.status }}</td><td>{{ item.submittedByName||'历史记录未保存' }}</td>
    <template v-if="tab==='operations'"><td>{{ item.paymentAt?formatDateTimeToSecond(item.paymentAt):item.paymentDate||'—' }}</td><td>
     <div v-if="item.status==='PENDING'&&canReview" class="fund-row-actions"><button data-test="confirm-funds" @click="review={item,approved:true}">确认</button><button data-test="reject-funds" @click="review={item,approved:false}">驳回</button></div>
     <template v-else-if="item.confirmedAmount!=null">¥ {{ money(item.confirmedAmount) }}<small>{{ item.reviewedByName||'—' }} · {{ formatDateTimeToSecond(item.reviewedAt) }}</small></template><span v-else>{{ item.status==='PENDING'?'待财务确认':'—' }}</span>
    </td></template><template v-else><td>¥ {{ money(item.balanceBefore) }}</td><td>¥ {{ money(item.balanceAfter) }}</td></template>
    <td><div class="fund-row-actions"><button @click="detail=item">详情</button><button @click="emit('customer',{id:item.customerId,customerName:item.customerName})">客户资金</button><button v-if="tab==='operations'&&canReview&&item.status==='APPROVED'&&item.ledgerId" @click="reversal=item;reversalReason='';error=''">冲正</button></div></td>
   </tr><tr v-if="!data.items.length"><td :colspan="tab==='ledger'?12:11" class="funds-empty">{{ loading?'正在读取资金记录…':'暂无符合条件的记录' }}</td></tr></tbody>
  </table></div>
  <footer class="funds-pagination"><span>共 {{ data.total }} 条 · 第 {{ data.page }} 页</span><button :disabled="loading||data.page<=1" @click="load(data.page-1)">上一页</button><button :disabled="loading||data.page*10>=data.total" @click="load(data.page+1)">下一页</button></footer>
  </template>
 </section>
 <FundOperationDialog v-if="newOpen" :customers="customers" :customer-id="filters.customerId" @close="newOpen=false" @saved="changed" @message="(text,kind)=>emit('message',text,kind)" />
 <FundReviewDialog v-if="review" :request="review.item" :approved="review.approved" @close="review=undefined" @saved="changed" @message="(text,kind)=>emit('message',text,kind)" />
 <div v-if="detail" class="dialog-mask">
  <section class="dialog-card" role="dialog" aria-modal="true">
   <header><div><h2>资金记录详情</h2><p>{{ detail.operationNo }} · {{ detail.customerName }}</p></div><button @click="detail=undefined">关闭</button></header>
   <div class="fund-detail">
    <p>处理状态：{{ statuses[detail.status]??detail.status }}</p>
    <p>资金来源：{{ fundSourceLabel(detail.fundSource) }}</p>
    <p>打款方式：{{ detail.paymentMethod||'—' }}</p>
    <p>银行流水号：{{ detail.referenceNo||'—' }}</p>
    <p>关联单据：{{ detail.sourceNo||'—' }}</p>
    <p>{{ detail.entryType==='REVERSAL'?'冲正原因':'登记备注' }}：{{ detail.remark||'—' }}</p>
    <template v-if="detail.status==='REVERSED'">
     <p>冲正原因：{{ detail.reversalReason||'—' }}</p>
     <p>冲正人员：{{ detail.reversalByName||'—' }}</p>
     <p>冲正时间：{{ formatDateTimeToSecond(detail.reversalAt) }}</p>
    </template>
    <template v-if="tab==='operations' && detail.status!=='PENDING'">
     <p>{{ detailReviewAction }}原因：{{ detail.reviewComment||'—' }}</p>
     <p>{{ detailReviewAction }}人员：{{ detail.reviewedByName||'—' }}</p>
     <p>{{ detailReviewAction }}时间：{{ formatDateTimeToSecond(detail.reviewedAt) }}</p>
    </template>
    <p v-else-if="tab==='operations'">资金确认：待财务确认</p>
    <template v-else>
     <p>操作人员：{{ detail.submittedByName||'—' }}</p>
     <p>操作时间：{{ formatDateTimeToSecond(detail.submittedAt) }}</p>
    </template>
   </div>
  </section>
 </div>
 <div v-if="reversal" class="dialog-mask"><section class="dialog-card" role="dialog" aria-modal="true"><header><h2>冲正资金记录</h2><button :disabled="reversing" @click="reversal=undefined">关闭</button></header><form @submit.prevent="reverse"><p>{{ reversal.operationNo }} · {{ reversal.customerName }} · ¥ {{ money(reversal.confirmedAmount??reversal.amount) }}</p><label><span>冲正原因</span><textarea v-model="reversalReason" maxlength="1000" :disabled="reversing"></textarea></label><p v-if="error" class="form-error" role="alert">{{ error }}</p><footer><button class="primary-action" :disabled="reversing">确认冲正</button></footer></form></section></div>
</template>
<style scoped>
.fund-heading-actions{display:flex;flex-wrap:wrap;gap:10px;justify-content:flex-end}@media(max-width:650px){.funds-page-heading{flex-wrap:wrap}.fund-heading-actions{justify-content:flex-start}}
.funds-page-heading{display:flex;align-items:center;justify-content:space-between;gap:16px;margin-bottom:20px}h1{margin:0;font-size:25px}.funds-page-heading p{color:#64748b;margin:8px 0 0}.funds-filters{display:flex;flex-wrap:wrap;gap:12px;align-items:end;margin:16px 0}.funds-filters label{display:flex;flex-direction:column;gap:6px;min-width:130px}.funds-filters label:first-child{width:260px}.funds-filters input,.funds-filters select{padding:9px;border:1px solid #d4d9df;border-radius:4px;min-width:0}.funds-filters span{font-size:13px}.funds-summary{display:grid;grid-template-columns:repeat(4,minmax(0,1fr));gap:14px}.funds-summary>div{border:1px solid #dce3ec;border-radius:8px;padding:16px;background:#fff}.funds-summary span{display:block;color:#64748b;font-size:13px}.funds-summary strong{display:block;margin-top:8px;font-size:20px;overflow-wrap:anywhere}.funds-table-scroll{overflow:auto;border:1px solid #dce3ec;background:#fff}.funds-table-scroll table{border-collapse:collapse;min-width:1500px;width:100%;font-size:13px}.funds-table-scroll th,.funds-table-scroll td{padding:13px 12px;border-bottom:1px solid #e5e9ef;text-align:left;vertical-align:middle;white-space:nowrap}.funds-table-scroll th{background:#f8fafc}.funds-table-scroll small{display:block;color:#64748b;margin-top:5px}.fund-row-actions{display:flex;gap:8px}.fund-row-actions button,.funds-pagination button{border:1px solid #cbd5e1;background:#fff;border-radius:4px;padding:6px 10px;cursor:pointer}.fund-amount{font-variant-numeric:tabular-nums}.danger{color:#b91c1c}.funds-empty{text-align:center!important;padding:30px!important;color:#64748b}.funds-pagination{display:flex;justify-content:flex-end;align-items:center;gap:12px;margin-top:16px}.fund-detail{padding:20px 22px;white-space:pre-wrap;overflow-wrap:anywhere}@media(max-width:650px){.funds-page-heading{align-items:flex-start}.funds-summary{grid-template-columns:repeat(2,minmax(0,1fr))}.funds-filters label{flex:1;min-width:140px}.funds-filters label:first-child{width:100%}.funds-filters input{width:100%;box-sizing:border-box}.funds-page-heading .primary-action{white-space:nowrap}}
</style>
<style scoped>
.funds-table-scroll table { table-layout:auto; }
.funds-table-scroll th,.funds-table-scroll td { overflow:visible; text-overflow:clip; }
.funds-table-scroll td:nth-child(2) .text-action { max-width:190px; white-space:normal; overflow-wrap:anywhere; text-align:left; }
.funds-table-scroll td:nth-child(6),.funds-table-scroll td:nth-child(9) { white-space:normal; min-width:150px; }
.funds-table-scroll td:nth-child(10) small { white-space:normal; min-width:170px; }
</style>
