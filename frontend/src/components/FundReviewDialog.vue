<script setup lang="ts">
import { reactive, ref } from 'vue'
import { reviewCustomerFundRequest } from '../api/customer-funds'
const props=defineProps<{ request:{id:number;amount:number;operationNo?:string;customerName?:string}; approved:boolean }>()
const emit=defineEmits<{close:[];saved:[];message:[text:string,kind?:'success'|'error']}>()
const form=reactive({amount:Math.abs(props.request.amount),comment:''})
const saving=ref(false),error=ref('')
async function save(){
  error.value=''
  if(props.approved && (!Number.isFinite(form.amount)||form.amount<=0)){error.value='确认金额必须大于 0';return}
  if((!props.approved || form.amount!==Math.abs(props.request.amount))&&!form.comment.trim()){error.value=props.approved?'调整确认金额时必须填写原因':'驳回时必须填写原因';return}
  saving.value=true
  try{await reviewCustomerFundRequest(props.request.id,props.approved,form.comment.trim(),props.approved?form.amount:undefined);emit('message',props.approved?'资金已确认，客户余额已更新':'资金记录已驳回');emit('saved')}
  catch(cause){error.value=cause instanceof Error?cause.message:'确认失败'}finally{saving.value=false}
}
</script>
<template>
  <div class="dialog-mask"><section class="dialog-card" role="dialog" aria-modal="true" aria-labelledby="fund-review-title">
    <header><div><h2 id="fund-review-title">{{ approved?'资金确认':'驳回资金记录' }}</h2><p>{{ request.operationNo }} {{ request.customerName }}</p></div><button type="button" :disabled="saving" @click="emit('close')">关闭</button></header>
    <form @submit.prevent="save"><div class="form-grid">
      <label><span>登记金额</span><strong>¥ {{ Math.abs(request.amount).toFixed(2) }}</strong></label>
      <label v-if="approved"><span>财务确认金额</span><input data-test="fund-confirmed-amount" v-model.number="form.amount" type="number" step="0.0001" min="0.0001" :disabled="saving"></label>
      <label class="wide-field"><span>{{ approved?'确认原因（调整金额时必填）':'驳回原因' }}</span><textarea data-test="fund-review-reason" v-model="form.comment" maxlength="1000" :disabled="saving"></textarea></label>
    </div><p v-if="error" class="form-error" role="alert">{{ error }}</p><footer><button type="button" class="secondary-action" :disabled="saving" @click="emit('close')">取消</button><button data-test="submit-fund-review" class="primary-action" :disabled="saving">{{ saving?'正在处理…':approved?'确认资金':'确认驳回' }}</button></footer></form>
  </section></div>
</template>
