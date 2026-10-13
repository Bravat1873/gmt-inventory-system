<script setup lang="ts">
import { computed, onMounted, reactive, ref, watch } from 'vue'
import { loadCustomerFundOrders, submitCustomerDeposit, type FundCustomer, type FundOrderOption, type FundSource } from '../api/customer-funds'
import FuzzyPicker from './FuzzyPicker.vue'
const props=defineProps<{customers:FundCustomer[];customerId?:number|null}>()
const emit=defineEmits<{close:[];saved:[];message:[text:string,kind?:'success'|'error']}>()
const form=reactive({customerId:props.customerId??null,direction:'IN',fundSource:'DOMESTIC' as FundSource,amount:'' as string|number,paymentAt:'',paymentMethod:'银行转账',referenceNo:'',remark:'',orderId:null as number|null})
const now=new Date();form.paymentAt=`${now.getFullYear()}-${String(now.getMonth()+1).padStart(2,'0')}-${String(now.getDate()).padStart(2,'0')}T${String(now.getHours()).padStart(2,'0')}:${String(now.getMinutes()).padStart(2,'0')}`
const orders=ref<FundOrderOption[]>([]),saving=ref(false),loadingOrders=ref(false),error=ref('');let generation=0
const customers=computed(()=>props.customers.filter(c=>c.enabled).map(c=>({id:c.id,label:`${c.customerName} · ${c.customerCode}`,searchText:c.customerCode})))
const orderOptions=computed(()=>orders.value.map(o=>({id:o.id,label:o.orderNo})))
const balance=computed(()=>props.customers.find(c=>c.id===form.customerId)?.balance??0)
async function loadOrders(){const id=form.customerId;const token=++generation;form.orderId=null;orders.value=[];error.value='';if(!id)return;loadingOrders.value=true;try{const data=await loadCustomerFundOrders(id);if(token===generation)orders.value=data}catch(cause){if(token===generation)error.value=cause instanceof Error?cause.message:'订单加载失败'}finally{if(token===generation)loadingOrders.value=false}}
watch(()=>form.customerId,loadOrders);onMounted(loadOrders)
async function save(){
 error.value='';const amount=Number(form.amount)
 if(!form.customerId){error.value='请选择客户';return}
 if(!Number.isFinite(amount)||amount<=0){error.value='资金金额必须大于 0';return}
 if(!form.paymentAt||!form.paymentMethod.trim()){error.value='请填写打款时间和方式';return}
 if(form.direction==='OUT'&&amount>balance.value){error.value='退款金额不能超过当前客户可用余额';return}
 saving.value=true
 try{await submitCustomerDeposit(form.customerId,{fundSource:form.fundSource,amount:form.direction==='OUT'?-amount:amount,paymentDate:form.paymentAt.slice(0,10),paymentAt:form.paymentAt.length===16?`${form.paymentAt}:00`:form.paymentAt,paymentMethod:form.paymentMethod.trim(),referenceNo:form.referenceNo.trim()||undefined,remark:form.remark.trim()||undefined,orderId:form.orderId??undefined});emit('message','资金记录已提交，等待财务确认');emit('saved')}
 catch(cause){error.value=cause instanceof Error?cause.message:'登记失败'}finally{saving.value=false}
}
</script>
<template>
 <div class="dialog-mask"><section class="dialog-card" role="dialog" aria-modal="true" aria-labelledby="fund-operation-title">
 <header><div><h2 id="fund-operation-title">新增客户资金记录</h2><p>财务确认后更新客户余额</p></div><button type="button" :disabled="saving" @click="emit('close')">关闭</button></header>
 <form @submit.prevent="save"><div class="form-grid">
 <label><span>客户名称</span><FuzzyPicker data-test="fund-customer-picker" v-model="form.customerId" :options="customers" placeholder="搜索客户名称或编码" :disabled="saving" /></label>
 <label><span>收付款类型</span><select data-test="fund-direction" v-model="form.direction" :disabled="saving"><option value="IN">收款</option><option value="OUT">退款</option></select></label>
 <label><span>资金来源</span><select data-test="fund-source" v-model="form.fundSource" :disabled="saving"><option value="FOREX">外汇</option><option value="DOMESTIC">国内</option></select></label>
 <label><span>资金金额</span><input data-test="fund-operation-amount" v-model="form.amount" type="number" min="0.0001" step="0.0001" :disabled="saving"></label>
 <label><span>当前可用余额</span><strong>¥ {{ Number(balance).toFixed(2) }}</strong></label>
 <label><span>{{ form.direction==='OUT'?'实际退款时间':'实际打款时间' }}</span><input data-test="fund-payment-time" v-model="form.paymentAt" type="datetime-local" step="1" :disabled="saving"></label>
 <label><span>打款方式</span><input v-model="form.paymentMethod" maxlength="60" :disabled="saving"></label>
 <label><span>银行流水号</span><input v-model="form.referenceNo" maxlength="100" :disabled="saving"></label>
 <label><span>关联订单（选填）</span><FuzzyPicker v-model="form.orderId" :options="orderOptions" placeholder="预存款可不关联订单" :disabled="saving||loadingOrders" /></label>
 <label class="wide-field"><span>备注</span><textarea v-model="form.remark" maxlength="1000" :disabled="saving"></textarea></label>
 </div><p v-if="error" class="form-error" role="alert">{{ error }}</p><footer><button type="button" class="secondary-action" :disabled="saving" @click="emit('close')">取消</button><button data-test="submit-fund-operation" class="primary-action" :disabled="saving||loadingOrders">{{ saving?'正在提交…':'提交资金记录' }}</button></footer></form>
 </section></div>
</template>
