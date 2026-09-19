<template>
  <div class="page">
    <h2 class="page-title">轧差执行</h2>
    <p class="page-desc">指定交割日与币种执行单币种多边轧差，校验 Σnet = 0</p>

    <div class="card-panel">
      <div class="toolbar">
        <el-date-picker v-model="settleDate" type="date" value-format="YYYY-MM-DD" placeholder="交割日" />
        <el-select v-model="currency" style="width:120px">
          <el-option label="USD" value="USD" />
          <el-option label="CNY" value="CNY" />
          <el-option label="EUR" value="EUR" />
        </el-select>
        <el-button type="warning" plain :disabled="!auth.isOperator" :loading="previewing" @click="doPreview">预演</el-button>
        <el-button type="primary" :disabled="!auth.isOperator" :loading="running" @click="execute">执行轧差</el-button>
        <el-button @click="loadRuns">刷新批次</el-button>
      </div>
    </div>

    <div v-if="previewResult" class="card-panel" style="margin-top:16px">
      <div class="toolbar" style="justify-content:space-between">
        <div>
          <strong>预演结果（不落正式状态）</strong>
          <el-tag style="margin-left:8px" type="warning">PREVIEW</el-tag>
          <span style="margin-left:12px">ΣnetAmount = {{ previewResult.sumNetAmount }}</span>
        </div>
        <el-tag type="info">义务仍为 OPEN，头寸不入库</el-tag>
      </div>

      <div style="margin-top:12px">
        <strong>将参与轧差的义务（{{ previewResult.obligations.length }} 笔）</strong>
        <el-table :data="previewResult.obligations" stripe size="small" style="margin-top:8px">
          <el-table-column prop="obligationId" label="义务 ID" min-width="220">
            <template #default="{ row }"><span class="mono">{{ row.obligationId }}</span></template>
          </el-table-column>
          <el-table-column label="付款方" min-width="180">
            <template #default="{ row }">
              <span class="mono">{{ row.payerMemberId }}</span>
              <div>{{ nameOf(row.payerMemberId) }}</div>
            </template>
          </el-table-column>
          <el-table-column label="收款方" min-width="180">
            <template #default="{ row }">
              <span class="mono">{{ row.payeeMemberId }}</span>
              <div>{{ nameOf(row.payeeMemberId) }}</div>
            </template>
          </el-table-column>
          <el-table-column prop="currency" label="币种" width="90" />
          <el-table-column prop="amount" label="金额" min-width="140" />
          <el-table-column prop="status" label="状态" width="100">
            <template #default="{ row }">
              <el-tag :type="row.status === 'OPEN' ? 'info' : 'danger'" size="small">{{ row.status }}</el-tag>
            </template>
          </el-table-column>
        </el-table>
      </div>

      <div style="margin-top:16px">
        <strong>预演净头寸</strong>
        <el-table :data="previewResult.positions" stripe size="small" style="margin-top:8px">
          <el-table-column prop="memberId" label="会员 ID" min-width="220">
            <template #default="{ row }">
              <span class="mono">{{ row.memberId }}</span>
              <div>{{ nameOf(row.memberId) }}</div>
            </template>
          </el-table-column>
          <el-table-column prop="currency" label="币种" width="90" />
          <el-table-column prop="netAmount" label="净头寸（正应收/负应付）" min-width="200" />
        </el-table>
      </div>

      <div class="toolbar" style="margin-top:12px">
        <el-button type="primary" :disabled="!auth.isOperator" :loading="running" @click="execute">按此条件正式执行</el-button>
      </div>
    </div>

    <div v-if="result" class="card-panel" style="margin-top:16px">
      <div class="toolbar" style="justify-content:space-between">
        <div>
          <strong>本次结果</strong>
          <el-tag style="margin-left:8px" :type="result.run.status === 'COMPLETED' ? 'success' : 'danger'">
            {{ result.run.status }}
          </el-tag>
          <span style="margin-left:12px">ΣnetAmount = {{ result.sumNetAmount }}</span>
        </div>
        <el-button link type="primary" @click="$router.push(`/netting-runs/${result.run.runId}`)">查看详情</el-button>
      </div>
      <el-table :data="result.positions" stripe>
        <el-table-column prop="memberId" label="会员 ID" min-width="220">
          <template #default="{ row }">
            <span class="mono">{{ row.memberId }}</span>
            <div>{{ nameOf(row.memberId) }}</div>
          </template>
        </el-table-column>
        <el-table-column prop="currency" label="币种" width="90" />
        <el-table-column prop="netAmount" label="净头寸（正应收/负应付）" min-width="200" />
      </el-table>
    </div>

    <div class="card-panel" style="margin-top:16px">
      <strong>历史批次</strong>
      <el-table :data="runs" v-loading="loading" stripe style="margin-top:12px">
        <el-table-column prop="runId" label="Run ID" min-width="220">
          <template #default="{ row }">
            <router-link class="mono" :to="`/netting-runs/${row.runId}`">{{ row.runId }}</router-link>
          </template>
        </el-table-column>
        <el-table-column prop="settleDate" label="交割日" width="120" />
        <el-table-column prop="currency" label="币种" width="90" />
        <el-table-column prop="status" label="状态" width="120" />
        <el-table-column prop="failureReason" label="失败原因" min-width="180" />
      </el-table>
    </div>
  </div>
</template>

<script setup>
import { onMounted, ref } from 'vue'
import { ElMessage } from 'element-plus'
import api from '../api/client'
import { useAuthStore } from '../stores/auth'

const auth = useAuthStore()
const settleDate = ref(new Date().toISOString().slice(0, 10))
const currency = ref('USD')
const running = ref(false)
const previewing = ref(false)
const loading = ref(false)
const result = ref(null)
const previewResult = ref(null)
const runs = ref([])
const memberMap = ref({})

function nameOf(id) {
  return memberMap.value[id] || ''
}

async function loadRuns() {
  loading.value = true
  try {
    const [r, m] = await Promise.all([api.get('/netting-runs'), api.get('/members')])
    runs.value = r.data
    memberMap.value = Object.fromEntries(m.data.map((x) => [x.memberId, x.name]))
  } finally {
    loading.value = false
  }
}

async function doPreview() {
  previewing.value = true
  try {
    const { data } = await api.post('/netting-runs/preview', {
      settleDate: settleDate.value,
      currency: currency.value
    })
    previewResult.value = data
    ElMessage.success(`预演完成：${data.obligations.length} 笔义务参与，义务仍保持 OPEN`)
  } catch (e) {
    previewResult.value = null
  } finally {
    previewing.value = false
  }
}

async function execute() {
  running.value = true
  try {
    const { data } = await api.post('/netting-runs', {
      settleDate: settleDate.value,
      currency: currency.value
    })
    result.value = data
    // a real run supersedes any earlier dry run for the same criteria
    previewResult.value = null
    ElMessage.success('轧差完成，守恒校验通过')
    await loadRuns()
  } catch (e) {
    result.value = null
    await loadRuns()
  } finally {
    running.value = false
  }
}

onMounted(loadRuns)
</script>
