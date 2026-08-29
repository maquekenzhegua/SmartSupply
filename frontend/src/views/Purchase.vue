<template>
  <div>
    <el-card>
      <template #header>
        <div style="display: flex; justify-content: space-between"><span>采购单</span><el-button type="primary" @click="openCreate">新建采购单</el-button></div>
      </template>
      <el-table :data="rows" v-loading="loading" @row-click="viewDetail">
        <el-table-column prop="order_no" label="单号" width="160" />
        <el-table-column prop="supplier_name" label="供应商" min-width="140" />
        <el-table-column prop="status" label="状态" width="110"><template #default="{ row }"><el-tag :type="statusType(row.status as string)">{{ row.status }}</el-tag></template></el-table-column>
        <el-table-column prop="total_amount" label="金额" width="110" />
        <el-table-column prop="created_at" label="创建时间" width="170" />
        <el-table-column label="操作" width="200">
          <template #default="{ row }">
            <el-button size="small" @click.stop="viewDetail(row)">详情</el-button>
            <el-button size="small" @click.stop="changeStatus(row, 'APPROVED')" v-if="row.status==='DRAFT'">审核</el-button>
            <el-button size="small" type="danger" @click.stop="remove(row)">删除</el-button>
          </template>
        </el-table-column>
      </el-table>
      <el-pagination style="margin-top:12px; justify-content:flex-end" v-model:current-page="page" :page-size="size" :total="total" layout="prev, pager, next" @current-change="load" />
    </el-card>

    <el-dialog v-model="detailVisible" title="采购单详情" width="640px">
      <div v-if="detail">
        <p>单号：{{ detail.order_no }} | 状态：{{ detail.status }} | 金额：{{ detail.total_amount }}</p>
        <el-table :data="(detail.items as Record<string, unknown>[]) || []">
          <el-table-column prop="sku_code" label="SKU" /><el-table-column prop="quantity" label="数量" /><el-table-column prop="unit_price" label="单价" /><el-table-column prop="amount" label="小计" />
        </el-table>
      </div>
    </el-dialog>

    <el-dialog v-model="createVisible" title="新建采购单" width="640px">
      <el-form label-width="90px">
        <el-form-item label="供应商ID"><el-input v-model="form.supplierId" type="number" placeholder="先在供应商页查看ID" /></el-form-item>
        <el-form-item label="备注"><el-input v-model="form.remark" /></el-form-item>
        <el-form-item label="明细">
          <div v-for="(it, idx) in form.items" :key="idx" style="display:flex; gap:8px; margin-bottom:8px">
            <el-input v-model="it.skuId" placeholder="SKU ID" style="width:100px" /><el-input v-model="it.quantity" placeholder="数量" style="width:100px" /><el-input v-model="it.unitPrice" placeholder="单价" style="width:100px" /><el-button size="small" type="danger" @click="form.items.splice(idx,1)">删</el-button>
          </div>
          <el-button size="small" @click="form.items.push({ skuId:'', quantity:1, unitPrice:0 })">添加一行</el-button>
        </el-form-item>
      </el-form>
      <template #footer><el-button @click="createVisible=false">取消</el-button><el-button type="primary" @click="submit">提交</el-button></template>
    </el-dialog>
  </div>
</template>

<script setup lang="ts">
import { ref, onMounted } from 'vue'
import { ElMessage, ElMessageBox } from 'element-plus'
import { api } from '@/api'
const rows = ref<Record<string, unknown>[]>([]), loading = ref(false)
const page = ref(1), size = ref(10), total = ref(0)
const detailVisible = ref(false), detail = ref<Record<string, unknown> | null>(null)
const createVisible = ref(false)
const form = ref<{ supplierId: string; remark: string; items: { skuId: string; quantity: number; unitPrice: number }[] }>({ supplierId: '', remark: '', items: [{ skuId: '', quantity: 1, unitPrice: 0 }] })
function statusType(s: string) { return s==='DRAFT' ? 'info' : s==='APPROVED' ? 'success' : s==='CANCELLED' ? 'danger' : 'warning' }
async function load() {
  loading.value = true
  try { const d = await api.purchaseOrders({ page: page.value, size: size.value }); rows.value = d.records || []; total.value = d.total || 0 }
  finally { loading.value = false }
}
async function viewDetail(row: Record<string, unknown>) {
  const d = await api.poDetail(row.id as number); detail.value = d; detailVisible.value = true
}
function openCreate() { form.value = { supplierId: '', remark: '', items: [{ skuId: '', quantity: 1, unitPrice: 0 }] }; createVisible.value = true }
async function submit() {
  await api.createPo({ supplierId: Number(form.value.supplierId) || null, remark: form.value.remark, items: form.value.items.map(i => ({ skuId: Number(i.skuId), quantity: Number(i.quantity), unitPrice: Number(i.unitPrice) })) })
  ElMessage.success('创建成功'); createVisible.value = false; load()
}
async function changeStatus(row: Record<string, unknown>, s: string) {
  await api.updatePoStatus(row.id as number, s); ElMessage.success('已更新'); load()
}
async function remove(row: Record<string, unknown>) {
  await ElMessageBox.confirm('确认删除该采购单？','提示')
  await api.deletePo(row.id as number); ElMessage.success('已删除'); load()
}
onMounted(load)
</script>
