<script setup lang="ts">
export interface FlowStage {
  key: string
  label: string
  value: number
  caption: string
}

defineProps<{ stages: FlowStage[]; selected?: string }>()
defineEmits<{ select: [category: string] }>()
</script>

<template>
  <div class="lightning-flow" aria-label="订单到交付业务流程">
    <template v-for="(stage, index) in stages" :key="stage.key">
      <button
        class="lightning-flow-stage"
        :class="{ active: selected === stage.key }"
        :data-flow-stage="stage.key"
        @click="$emit('select', stage.key)"
      >
        <span class="flow-step">{{ index + 1 }}</span>
        <span class="flow-copy">
          <strong>{{ stage.value.toLocaleString() }}</strong>
          <b>{{ stage.label }}</b>
          <small>{{ stage.caption }}</small>
        </span>
      </button>
      <span v-if="index < stages.length - 1" class="flow-connector" aria-hidden="true">→</span>
    </template>
  </div>
</template>
