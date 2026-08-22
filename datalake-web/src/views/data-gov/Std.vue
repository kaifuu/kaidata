<template>
  <div class="dl-card">
    <div class="card-title"><span>数据标准</span><span class="role-tag">系统管理员</span></div>
    <el-tabs v-model="tab">
      <!-- 数据元 -->
      <el-tab-pane label="数据元" name="element">
        <div class="filter-bar">
          <el-select v-model="f.category" placeholder="业务分类" clearable size="small" style="width:130px">
            <el-option v-for="c in CATEGORIES" :key="c" :label="c" :value="c" />
          </el-select>
          <el-select v-model="f.status" placeholder="状态" clearable size="small" style="width:100px">
            <el-option label="正常" value="NORMAL" />
            <el-option label="停用" value="DISABLED" />
          </el-select>
          <el-input v-model="f.keyword" placeholder="编码/名称/英文名" clearable size="small" style="width:200px" @keyup.enter="loadEl" />
          <el-button size="small" type="primary" @click="loadEl">搜索</el-button>
          <el-button size="small" @click="exportExcel" style="margin-left:auto">导出 Excel</el-button>
          <el-button size="small" @click="importClick">导入 Excel</el-button>
          <input ref="importInput" type="file" accept=".xlsx,.xls" style="display:none" @change="doImport" />
          <el-button size="small" type="primary" @click="openEl()">
            <el-icon><Plus /></el-icon> 新增数据元
          </el-button>
        </div>
        <el-table :data="elements" size="small" stripe border v-loading="loading">
          <el-table-column prop="code" label="编码" width="130" />
          <el-table-column prop="name" label="名称" min-width="100" />
          <el-table-column prop="en_name" label="英文名" width="110" />
          <el-table-column label="分类" width="80">
            <template #default="{ row }"><el-tag size="small" v-if="row.category">{{ row.category }}</el-tag></template>
          </el-table-column>
          <el-table-column label="类型" width="110">
            <template #default="{ row }">{{ row.data_type }}<span class="muted" v-if="row.length">({{ row.length }}{{ row.scale_ ? ',' + row.scale_ : '' }})</span></template>
          </el-table-column>
          <el-table-column prop="unit" label="单位" width="60" />
          <el-table-column label="引用代码集" width="110">
            <template #default="{ row }"><span v-if="row.code_set_name">{{ row.code_set_name }}</span><span v-else class="muted">-</span></template>
          </el-table-column>
          <el-table-column label="引用" width="70">
            <template #default="{ row }"><el-link v-if="row.ref_cnt > 0" type="primary" @click="openElRefs(row)">{{ row.ref_cnt }}</el-link><span v-else class="muted">0</span></template>
          </el-table-column>
          <el-table-column label="操作" width="230">
            <template #default="{ row }"><el-button link size="small" type="primary" @click="openEl(row)">编辑</el-button><el-button link size="small" type="success" @click="openLand(row)">落标</el-button><el-button link size="small" @click="openVersions(row)">版本</el-button><el-button link size="small" type="danger" @click="delEl(row)">删除</el-button></template>
          </el-table-column>
        </el-table>
      </el-tab-pane>

      <!-- 代码集 -->
      <el-tab-pane label="代码集" name="code">
        <div class="filter-bar">
          <el-select v-model="cf.category" placeholder="分类" clearable size="small" style="width:130px">
            <el-option v-for="c in CODE_CATEGORIES" :key="c" :label="c" :value="c" />
          </el-select>
          <el-input v-model="cf.keyword" placeholder="编码/名称" clearable size="small" style="width:180px" @keyup.enter="loadCs" />
          <el-button size="small" type="primary" @click="loadCs">搜索</el-button>
          <el-button size="small" type="primary" @click="openCs()" style="margin-left:auto">
            <el-icon><Plus /></el-icon> 新增代码集
          </el-button>
        </div>
        <el-table :data="codeSets" size="small" stripe border v-loading="loadingCs">
          <el-table-column prop="code" label="编码" width="130" />
          <el-table-column prop="name" label="名称" min-width="120" />
          <el-table-column label="分类" width="80">
            <template #default="{ row }"><el-tag size="small" v-if="row.category">{{ row.category }}</el-tag></template>
          </el-table-column>
          <el-table-column prop="item_cnt" label="代码项" width="70" />
          <el-table-column label="被引用" width="70">
            <template #default="{ row }"><el-link v-if="row.ref_cnt > 0" type="primary" @click="openCsRefs(row)">{{ row.ref_cnt }}</el-link><span v-else class="muted">0</span></template>
          </el-table-column>
          <el-table-column prop="description" label="说明" min-width="140" show-overflow-tooltip />
          <el-table-column label="操作" width="210">
            <template #default="{ row }"><el-button link size="small" type="success" @click="openItems(row)">代码项</el-button><el-button link size="small" type="primary" @click="openCs(row)">编辑</el-button><el-button link size="small" type="danger" @click="delCs(row)">删除</el-button></template>
          </el-table-column>
        </el-table>
      </el-tab-pane>

      <!-- 落标概况 -->
      <el-tab-pane label="落标概况" name="landing">
        <div v-loading="landingLoading">
          <el-row :gutter="12" v-if="landing">
            <el-col :span="6"><div class="ring-box"><v-chart :option="rateOption" :theme="theme" autoresize style="height:150px" /></div></el-col>
            <el-col :span="18">
              <div class="muted" style="margin-bottom:6px">已落标 {{ landing.landed }} / {{ landing.total }} 字段 · 引用最多的数据元 Top5</div>
              <el-table :data="landing.topElements || []" size="small" border max-height="140">
                <el-table-column prop="name" label="数据元" min-width="140" /><el-table-column prop="code" label="编码" width="120" /><el-table-column prop="refs" label="引用数" width="80" />
              </el-table>
            </el-col>
          </el-row>
          <div style="margin:12px 0 6px" class="muted">未落标字段（未关联数据元）</div>
          <el-table :data="landing?.unlanded || []" size="small" border max-height="200">
            <el-table-column prop="field" label="字段" min-width="120" /><el-table-column prop="data_type" label="类型" width="110" /><el-table-column prop="table_name" label="模型表" min-width="120" /><el-table-column prop="model_name" label="模型" min-width="120" />
          </el-table>
          <div style="margin:12px 0 6px; display:flex; align-items:center; gap:8px; flex-wrap:wrap">
            <span class="muted">合规扫描（类型/长度静态检查 + 真实数据活体检查）</span>
            <el-checkbox v-model="complianceLive" size="small">活体扫描（按落标连接查真实数据）</el-checkbox>
            <el-button link size="small" type="primary" @click="loadCompliance">开始扫描</el-button>
            <el-button link size="small" type="success" @click="openRecommend" style="margin-left:auto">落标推荐</el-button>
          </div>
          <template v-if="compliance">
            <div class="muted">
              静态：共 {{ compliance.total }} 个已落标字段，通过 {{ compliance.pass }}，
              <span :style="compliance.fail ? 'color:#e54d4d' : ''">不一致 {{ compliance.fail }}</span>
              <template v-if="compliance.live"> · 活体：检查 {{ compliance.liveChecked }} 项，
                <span :style="compliance.liveFail ? 'color:#e54d4d' : ''">违规 {{ compliance.liveFail }}</span>
              </template>
            </div>
            <el-table :data="compliance.failList || []" size="small" border max-height="200" style="margin-top:6px">
              <el-table-column prop="field" label="字段" min-width="110" /><el-table-column prop="field_type" label="字段类型" width="100" /><el-table-column prop="element" label="数据元" min-width="110" /><el-table-column prop="element_type" label="数据元类型" width="100" /><el-table-column prop="table_name" label="模型表" min-width="110" /><el-table-column label="检查项" width="80"><template #default="{ row }"><el-tag size="small" type="warning">{{ row.check_type }}</el-tag></template></el-table-column><el-table-column prop="reason" label="原因" min-width="180" show-overflow-tooltip />
            </el-table>
            <template v-if="compliance.live && (compliance.liveFailList || []).length">
              <div class="muted" style="margin:10px 0 4px">活体扫描违规（真实数据不符合标准）</div>
              <el-table :data="compliance.liveFailList" size="small" border max-height="200">
                <el-table-column prop="table_name" label="表" min-width="130" /><el-table-column prop="column_name" label="列" width="110" /><el-table-column prop="element" label="数据元" min-width="110" /><el-table-column label="检查项" width="80"><template #default="{ row }"><el-tag size="small" type="danger">{{ row.check_type }}</el-tag></template></el-table-column><el-table-column prop="violate" label="违规行数" width="90" /><el-table-column prop="reason" label="说明" min-width="180" show-overflow-tooltip />
              </el-table>
            </template>
          </template>
        </div>
      </el-tab-pane>
    </el-tabs>

    <!-- 数据元编辑 -->
    <el-dialog v-model="elDlg" :title="deForm.id ? '编辑数据元' : '新增数据元'" width="580px">
      <el-form :model="deForm" label-width="80px">
        <el-form-item label="编码">
          <el-input v-model="deForm.code" placeholder="如 DE_SEX" />
        </el-form-item>
        <el-form-item label="名称">
          <el-input v-model="deForm.name" />
        </el-form-item>
        <el-form-item label="英文名">
          <el-input v-model="deForm.en_name" placeholder="如 sex_code" />
        </el-form-item>
        <el-form-item label="业务分类">
          <el-select v-model="deForm.category" clearable placeholder="选择分类" style="width:100%">
            <el-option v-for="c in CATEGORIES" :key="c" :label="c" :value="c" />
          </el-select>
        </el-form-item>
        <el-form-item label="数据类型">
          <el-input v-model="deForm.data_type" placeholder="VARCHAR / INT / DECIMAL" style="width:220px" />
        </el-form-item>
        <el-form-item label="长度精度">
          <el-input-number v-model="deForm.length" :min="0" controls-position="right" style="width:110px" />
          <el-input-number v-model="deForm.precision_" :min="0" controls-position="right" style="width:100px" />
          <el-input-number v-model="deForm.scale_" :min="0" controls-position="right" style="width:100px" />
        </el-form-item>
        <el-form-item label="取值域">
          <el-select v-model="deForm.code_set_id" clearable placeholder="引用代码集（自动生成取值域）" style="width:100%">
            <el-option v-for="cs in codeSets" :key="cs.id" :label="cs.code + ' - ' + cs.name" :value="cs.id" />
          </el-select>
          <el-input v-model="deForm.value_domain" type="textarea" :rows="2" style="margin-top:6px" :disabled="!!deForm.code_set_id" :placeholder="deForm.code_set_id ? '已关联代码集，保存后自动生成' : '或手填取值域说明'" />
        </el-form-item>
        <el-form-item label="计量单位">
          <el-input v-model="deForm.unit" style="width:140px" />
        </el-form-item>
        <el-form-item label="数据格式">
          <el-input v-model="deForm.data_format" placeholder="如 yyyy-MM-dd" />
        </el-form-item>
        <el-form-item label="安全分级">
          <el-select v-model="deForm.security_level" clearable style="width:100%">
            <el-option v-for="s in SECURITY_LEVELS" :key="s.value" :label="s.label" :value="s.value" />
          </el-select>
        </el-form-item>
        <el-form-item label="负责人">
          <el-input v-model="deForm.owner" />
        </el-form-item>
        <el-form-item label="定义">
          <el-input v-model="deForm.definition" type="textarea" :rows="2" />
        </el-form-item>
        <el-form-item label="状态">
          <el-radio-group v-model="deForm.status">
            <el-radio value="NORMAL">正常</el-radio>
            <el-radio value="DISABLED">停用</el-radio>
          </el-radio-group>
        </el-form-item>
      </el-form>
      <template #footer>
        <el-button @click="elDlg = false">取消</el-button>
        <el-button type="primary" @click="saveEl">保存</el-button>
      </template>
    </el-dialog>

    <!-- 数据元引用明细 -->
    <el-dialog v-model="elRefsDlg" :title="'引用明细 - ' + (curEl?.name || '')" width="620px">
      <div class="muted" style="margin-bottom:8px">共被 {{ elRefs.length }} 处模型字段引用</div>
      <el-table :data="elRefs" size="small" border max-height="360">
        <el-table-column prop="model_name" label="模型" width="140" />
        <el-table-column prop="table_name" label="表" width="160" />
        <el-table-column prop="field_name" label="字段" min-width="120" />
        <el-table-column prop="data_type" label="类型" width="110" />
      </el-table>
    </el-dialog>

    <!-- 代码集编辑 -->
    <el-dialog v-model="csDlg" :title="csForm.id ? '编辑代码集' : '新增代码集'" width="460px">
      <el-form :model="csForm" label-width="60px">
        <el-form-item label="编码">
          <el-input v-model="csForm.code" />
        </el-form-item>
        <el-form-item label="名称">
          <el-input v-model="csForm.name" />
        </el-form-item>
        <el-form-item label="分类">
          <el-select v-model="csForm.category" clearable style="width:100%">
            <el-option v-for="c in CODE_CATEGORIES" :key="c" :label="c" :value="c" />
          </el-select>
        </el-form-item>
        <el-form-item label="说明">
          <el-input v-model="csForm.description" type="textarea" :rows="2" />
        </el-form-item>
      </el-form>
      <template #footer>
        <el-button @click="csDlg = false">取消</el-button>
        <el-button type="primary" @click="saveCs">保存</el-button>
      </template>
    </el-dialog>

    <!-- 代码集被引用 -->
    <el-dialog v-model="csRefsDlg" :title="'被引用 - ' + (curCs?.name || '')" width="560px">
      <div class="muted" style="margin-bottom:8px">共被 {{ csRefs.length }} 个数据元引用</div>
      <el-table :data="csRefs" size="small" border max-height="360">
        <el-table-column prop="code" label="编码" width="130" />
        <el-table-column prop="name" label="名称" min-width="120" />
        <el-table-column prop="en_name" label="英文名" width="120" />
        <el-table-column prop="category" label="分类" width="80" />
      </el-table>
    </el-dialog>

    <!-- 代码项 -->
    <el-dialog v-model="itemDlg" :title="'代码项 - ' + (curSet?.name || '')" width="640px">
      <div style="margin-bottom:8px;display:flex;gap:6px;flex-wrap:wrap">
        <el-input v-model="newItem.code" size="small" placeholder="编码" style="width:110px" />
        <el-input v-model="newItem.name" size="small" placeholder="名称" style="width:140px" />
        <el-input-number v-model="newItem.sort" :min="0" size="small" style="width:100px" />
        <el-input v-model="newItem.remark" size="small" placeholder="备注" style="width:140px" />
        <el-button size="small" type="primary" @click="addItem">添加</el-button>
      </div>
      <el-table :data="codeItems" size="small" border max-height="320">
        <el-table-column prop="code" label="编码" width="120" />
        <el-table-column prop="name" label="名称" min-width="120" />
        <el-table-column prop="sort" label="排序" width="70" />
        <el-table-column label="启用" width="70">
          <template #default="{ row }"><el-switch v-model="row.is_enabled" @change="toggleItem(row)" /></template>
        </el-table-column>
        <el-table-column prop="remark" label="备注" min-width="120" show-overflow-tooltip />
        <el-table-column label="操作" width="120">
          <template #default="{ row }"><el-button link size="small" type="primary" @click="editItem(row)">改备注</el-button><el-button link size="small" type="danger" @click="delItem(row)">删除</el-button></template>
        </el-table-column>
      </el-table>
    </el-dialog>

    <!-- 标准落标 → 派生质量规则 -->
    <el-dialog v-model="landDlg" :title="'标准落标 - ' + (curEl?.name || '')" width="640px">
      <el-form :model="landForm" label-width="70px">
        <el-form-item label="数据源">
          <el-select v-model="landForm.dsId" placeholder="选择数据源（质量规则取数用）" style="width:100%">
            <el-option v-for="d in datasources" :key="d.id" :label="d.name + ' (' + d.type + ' ' + d.host + ':' + d.port + ')'" :value="d.id" />
          </el-select>
        </el-form-item>
        <el-form-item label="表名">
          <el-input v-model="landForm.tableName" placeholder="如 ods.dem_user" />
        </el-form-item>
        <el-form-item label="列名">
          <el-input v-model="landForm.columnName" placeholder="如 gender" />
        </el-form-item>
      </el-form>
      <div style="margin-bottom:8px"><el-button type="primary" size="small" @click="doLand">落标并生成质量规则</el-button></div>
      <div v-if="derivedRules.length" class="muted" style="margin:4px 0">派生的质量规则</div>
      <el-table :data="derivedRules" size="small" border max-height="120" style="margin-bottom:10px">
        <el-table-column prop="name" label="规则" min-width="200" />
        <el-table-column prop="dimension" label="维度" width="100" />
        <el-table-column prop="expression" label="表达式" min-width="220" show-overflow-tooltip />
      </el-table>
      <div class="muted" style="margin:4px 0">已落标</div>
      <el-table :data="landings" size="small" border max-height="180">
        <el-table-column prop="ds_name" label="数据源" width="140" />
        <el-table-column prop="table_name" label="表" min-width="130" />
        <el-table-column prop="column_name" label="列" width="100" />
        <el-table-column label="操作" width="80">
          <template #default="{ row }"><el-button link size="small" type="danger" @click="delLanding(row)">解除</el-button></template>
        </el-table-column>
      </el-table>
      <template #footer>
        <el-button @click="landDlg = false">关闭</el-button>
      </template>
    </el-dialog>

    <!-- 数据元版本历史 + 对比 -->
    <el-dialog v-model="verDlg" :title="'版本历史 - ' + (verEl?.name || '')" width="720px">
      <div style="display:flex; gap:8px; align-items:center; margin-bottom:8px">
        <el-select v-model="verA" size="small" placeholder="版本 A" style="width:140px"><el-option v-for="v in versions" :key="v.version_n" :label="'v' + v.version_n" :value="v.version_n" /></el-select>
        <span class="muted">对比</span>
        <el-select v-model="verB" size="small" placeholder="版本 B" style="width:140px"><el-option v-for="v in versions" :key="v.version_n" :label="'v' + v.version_n" :value="v.version_n" /></el-select>
        <el-button size="small" type="primary" :disabled="!verA || !verB || verA === verB" @click="compareVersions">对比</el-button>
        <span class="muted" style="margin-left:auto">编辑数据元自动生成"修改前快照"</span>
      </div>
      <el-table :data="versions" size="small" border max-height="180">
        <el-table-column prop="version_n" label="版本" width="70"><template #default="{ row }">v{{ row.version_n }}</template></el-table-column>
        <el-table-column prop="change_detail" label="说明" min-width="120" />
        <el-table-column prop="create_by" label="操作人" width="90" />
        <el-table-column prop="create_time" label="时间" width="160" />
      </el-table>
      <template v-if="verDiff">
        <div class="muted" style="margin:10px 0 4px">v{{ verDiff.v1 }} → v{{ verDiff.v2 }} 差异（{{ verDiff.changed.length }} 项）</div>
        <el-table :data="verDiff.changed" size="small" border max-height="200">
          <el-table-column prop="field" label="字段" width="130" />
          <el-table-column prop="old" label="旧值" min-width="140"><template #default="{ row }"><span class="muted">{{ row.old || '(空)' }}</span></template></el-table-column>
          <el-table-column prop="new" label="新值" min-width="140" />
        </el-table>
      </template>
    </el-dialog>

    <!-- 落标推荐 -->
    <el-dialog v-model="recDlg" title="落标推荐（列名 ↔ 数据元相似度）" width="860px">
      <div style="display:flex; gap:8px; margin-bottom:10px">
        <el-select v-model="recMetaId" filterable placeholder="选择元数据表（先在元数据采集登记）" size="small" style="width:340px" @change="loadRecommend">
          <el-option v-for="m in metaTables" :key="m.id" :label="(m.schema_name ? m.schema_name + '.' : '') + m.table_name" :value="m.id" />
        </el-select>
        <el-button size="small" type="primary" :disabled="!recMetaId" :loading="recLoading" @click="loadRecommend">分析</el-button>
      </div>
      <template v-if="recData">
        <div class="muted" style="margin-bottom:6px">{{ recData.tableName }} · 相似度 ≥60 才推荐，确认后一键落标</div>
        <el-table :data="recData.columns" size="small" border max-height="420">
          <el-table-column prop="column" label="列" width="130" />
          <el-table-column prop="type" label="类型" width="100" />
          <el-table-column prop="comment" label="注释" width="130" show-overflow-tooltip />
          <el-table-column label="推荐数据元（Top3）" min-width="420">
            <template #default="{ row }">
              <template v-if="row.suggestions?.length">
                <div v-for="s in row.suggestions" :key="s.id" class="rec-item">
                  <el-tag size="small" :type="s.score >= 90 ? 'success' : 'info'">{{ s.score }}</el-tag>
                  <span>{{ s.name }}（{{ s.code }} · {{ s.data_type }}）</span>
                  <el-button link size="small" type="primary" @click="landFromRec(s, row)">落标</el-button>
                </div>
              </template>
              <span v-else class="muted">无匹配</span>
            </template>
          </el-table-column>
        </el-table>
      </template>
      <template #footer><el-button @click="recDlg = false">关闭</el-button></template>
    </el-dialog>
  </div>
</template>

<script setup lang="ts">
import { computed, onMounted, reactive, ref, watch } from 'vue'
import { ElMessage, ElMessageBox } from 'element-plus'
import { Plus } from '@element-plus/icons-vue'
import { VChart } from '@/echarts'
import { api, errMsg } from '@/api'

const CATEGORIES = ['人员', '产品', '事件', '财务', '组织', '地理位置', '其他']
const CODE_CATEGORIES = ['枚举', '状态码', '字典', '行业代码', '其他']
const SECURITY_LEVELS = [
  { label: '公开', value: 'PUBLIC' },
  { label: '内部', value: 'INTERNAL' },
  { label: '敏感', value: 'SENSITIVE' },
]
const DEFAULT_EL = {
  id: null as number | null, code: '', name: '', en_name: '', category: '',
  data_type: 'VARCHAR', length: 64, precision_: 0, scale_: 0,
  unit: '', data_format: '', security_level: '', owner: '',
  code_set_id: 0, value_domain: '', definition: '', status: 'NORMAL',
}

const tab = ref('element')
const elements = ref<any[]>([])
const loading = ref(false)
const codeSets = ref<any[]>([])
const loadingCs = ref(false)
const f = reactive<any>({ category: '', status: '', keyword: '' })
const cf = reactive<any>({ category: '', keyword: '' })

const elDlg = ref(false)
const deForm = reactive<any>({ ...DEFAULT_EL })
const csDlg = ref(false)
const csForm = reactive<any>({ id: null, code: '', name: '', category: '', description: '' })

const elRefsDlg = ref(false)
const curEl = ref<any>(null)
const elRefs = ref<any[]>([])
const csRefsDlg = ref(false)
const curCs = ref<any>(null)
const csRefs = ref<any[]>([])
const itemDlg = ref(false)
const curSet = ref<any>(null)
const codeItems = ref<any[]>([])
const newItem = reactive<any>({ code: '', name: '', sort: 1, is_enabled: true, remark: '' })

async function loadEl() {
  loading.value = true
  try { elements.value = await api.govElements(f.category, f.status, f.keyword) }
  catch (e:any) { ElMessage.error(errMsg(e)) }
  finally { loading.value = false }
}
async function loadCs() {
  loadingCs.value = true
  try { codeSets.value = await api.govCodeSets(cf.category, cf.keyword) }
  catch (e:any) { ElMessage.error(errMsg(e)) }
  finally { loadingCs.value = false }
}

function openEl(row?: any) {
  Object.assign(deForm, { ...DEFAULT_EL }, row || {})
  if (!deForm.code_set_id) deForm.code_set_id = 0
  elDlg.value = true
}
async function saveEl() {
  if (!deForm.code || !deForm.name) return ElMessage.warning('请填编码与名称')
  try { await api.govSaveElement({ ...deForm }); ElMessage.success('保存成功'); elDlg.value = false; await loadEl() }
  catch (e:any) { ElMessage.error(errMsg(e)) }
}
async function delEl(row: any) {
  await ElMessageBox.confirm('删除数据元 ' + row.code + '？', '提示', { type: 'warning' })
  try { await api.govDeleteElement(row.id); ElMessage.success('已删除'); await loadEl() }
  catch (e:any) { ElMessage.error(errMsg(e)) }
}
async function openElRefs(row: any) {
  curEl.value = row; elRefsDlg.value = true
  try { const r:any = await api.govElementRefs(row.id); elRefs.value = r.refs || [] }
  catch { elRefs.value = [] }
}

function openCs(row?: any) {
  Object.assign(csForm, { id: null, code: '', name: '', category: '', description: '' }, row || {})
  csDlg.value = true
}
async function saveCs() {
  try { await api.govSaveCodeSet({ ...csForm }); ElMessage.success('保存成功'); csDlg.value = false; await loadCs() }
  catch (e:any) { ElMessage.error(errMsg(e)) }
}
async function delCs(row: any) {
  await ElMessageBox.confirm('删除代码集 ' + row.code + '？引用它的数据元将解除关联', '提示', { type: 'warning' })
  try { await api.govDeleteCodeSet(row.id); ElMessage.success('已删除'); await loadCs() }
  catch (e:any) { ElMessage.error(errMsg(e)) }
}
async function openCsRefs(row: any) {
  curCs.value = row; csRefsDlg.value = true
  try { const r:any = await api.govCodeSetRefs(row.id); csRefs.value = r.refs || [] }
  catch { csRefs.value = [] }
}

async function openItems(row: any) {
  curSet.value = row; itemDlg.value = true
  Object.assign(newItem, { code: '', name: '', sort: 1, is_enabled: true, remark: '' })
  try { codeItems.value = await api.govCodeItems(row.id) }
  catch { codeItems.value = [] }
}
async function addItem() {
  if (!newItem.code || !newItem.name) return ElMessage.warning('填编码与名称')
  try {
    await api.govSaveCodeItem({ set_id: curSet.value.id, ...newItem })
    Object.assign(newItem, { code: '', name: '', sort: 1, is_enabled: true, remark: '' })
    codeItems.value = await api.govCodeItems(curSet.value.id)
    await loadEl()
  } catch (e:any) { ElMessage.error(errMsg(e)) }
}
async function toggleItem(row: any) {
  try { await api.govSaveCodeItem({ id: row.id, set_id: row.set_id, code: row.code, name: row.name, sort: row.sort, is_enabled: row.is_enabled, remark: row.remark }); await loadEl() }
  catch (e:any) { ElMessage.error(errMsg(e)); codeItems.value = await api.govCodeItems(curSet.value.id) }
}
async function editItem(row: any) {
  try {
    const res: any = await ElMessageBox.prompt('备注', '编辑代码项', { inputValue: row.remark || '' })
    await api.govSaveCodeItem({ id: row.id, set_id: row.set_id, code: row.code, name: row.name, sort: row.sort, is_enabled: row.is_enabled, remark: res.value })
    codeItems.value = await api.govCodeItems(curSet.value.id)
    await loadEl()
  } catch (e:any) { if (e !== 'cancel') ElMessage.error(errMsg(e)) }
}
async function delItem(row: any) {
  try { await api.govDeleteCodeItem(row.id); codeItems.value = await api.govCodeItems(curSet.value.id); await loadEl() }
  catch (e:any) { ElMessage.error(errMsg(e)) }
}

// ===== 标准落标（标准→质量通道） =====
const landDlg = ref(false)
const landForm = reactive<any>({ dsId: null as number | null, tableName: '', columnName: '' })
const datasources = ref<any[]>([])
const landings = ref<any[]>([])
const derivedRules = ref<any[]>([])
async function openLand(row: any) {
  curEl.value = row
  Object.assign(landForm, { dsId: null, tableName: '', columnName: '' })
  derivedRules.value = []
  landDlg.value = true
  try { if (!datasources.value.length) datasources.value = await api.govStdDatasources() } catch { datasources.value = [] }
  try { landings.value = await api.govStdLandings(row.id) } catch { landings.value = [] }
}
async function doLand() {
  if (!landForm.dsId || !landForm.tableName || !landForm.columnName) return ElMessage.warning('请填数据源/表名/列名')
  try {
    const r: any = await api.govStdLand({ elementId: curEl.value.id, dsId: landForm.dsId, tableName: landForm.tableName, columnName: landForm.columnName })
    derivedRules.value = r.rules || []
    ElMessage.success('已落标并派生 ' + (r.rules?.length || 0) + ' 条质量规则')
    landings.value = await api.govStdLandings(curEl.value.id)
  } catch (e:any) { ElMessage.error(errMsg(e)) }
}
async function delLanding(row: any) {
  try { await api.govStdDeleteLanding(row.id); ElMessage.success('已解除'); landings.value = await api.govStdLandings(curEl.value.id) }
  catch (e:any) { ElMessage.error(errMsg(e)) }
}

// ===== 落标概况 =====
const theme = 'tech-dark'
const landing = ref<any>(null); const landingLoading = ref(false); const compliance = ref<any>(null); const complianceLive = ref(false)
async function loadLanding() { landingLoading.value = true; try { landing.value = await api.govStdLandingStats() } catch (e: any) { ElMessage.error(errMsg(e)) } finally { landingLoading.value = false } }
async function loadCompliance() { try { compliance.value = await api.govStdComplianceScan(complianceLive.value) } catch (e: any) { ElMessage.error(errMsg(e)) } }
const rateOption = computed(() => {
  const v = landing.value?.rate || 0
  return { title: { text: v + '%', left: 'center', top: '34%', textStyle: { fontSize: 20, color: '#e6ecff' } },
    series: [{ type: 'pie', radius: ['60%', '78%'], silent: true, label: { show: false }, data: [{ value: v, itemStyle: { color: '#2ee6a6' } }, { value: 100 - v, itemStyle: { color: '#26314f' } }] }] }
})
watch(tab, (t) => { if (t === 'landing' && !landing.value) loadLanding() })

// ===== P1：Excel 导入导出 =====
const importInput = ref<HTMLInputElement | null>(null)
async function exportExcel() {
  try {
    const blob: Blob = await api.govElementExcel()
    const url = URL.createObjectURL(blob); const a = document.createElement('a')
    a.href = url; a.download = `数据元_${new Date().toISOString().slice(0, 10)}.xlsx`
    document.body.appendChild(a); a.click(); document.body.removeChild(a); URL.revokeObjectURL(url)
    ElMessage.success('Excel 已下载')
  } catch (e: any) { ElMessage.error(errMsg(e)) }
}
function importClick() { importInput.value?.click() }
async function doImport(ev: Event) {
  const file = (ev.target as HTMLInputElement).files?.[0]
  if (!file) return
  try {
    const r: any = await api.govElementImport(file)
    ElMessage.success(`导入完成：新增 ${r.inserted ?? 0}，更新 ${r.updated ?? 0}，跳过 ${r.skipped ?? 0}`)
    await loadEl(); await loadCs()
  } catch (e: any) { ElMessage.error(errMsg(e)) }
  (ev.target as HTMLInputElement).value = ''
}

// ===== P1：版本快照 =====
const verDlg = ref(false); const verEl = ref<any>(null); const versions = ref<any[]>([])
const verA = ref<number | null>(null); const verB = ref<number | null>(null); const verDiff = ref<any>(null)
async function openVersions(row: any) {
  verEl.value = row; verDlg.value = true; verDiff.value = null; verA.value = null; verB.value = null
  try { versions.value = await api.govElementVersions(row.id) } catch { versions.value = [] }
  if (versions.value.length >= 2) { verA.value = versions.value[versions.value.length - 1].version_n; verB.value = versions.value[0].version_n }
}
async function compareVersions() {
  if (!verEl.value || !verA.value || !verB.value) return
  try { verDiff.value = await api.govElementVersionCompare(verEl.value.id, verA.value, verB.value) }
  catch (e: any) { ElMessage.error(errMsg(e)) }
}

// ===== P1：落标推荐 =====
const recDlg = ref(false); const recMetaId = ref<number | null>(null); const recLoading = ref(false)
const metaTables = ref<any[]>([]); const recData = ref<any>(null)
async function openRecommend() {
  recDlg.value = true
  if (!metaTables.value.length) { try { metaTables.value = await api.govMetaList({}) } catch { metaTables.value = [] } }
}
async function loadRecommend() {
  if (!recMetaId.value) return
  recLoading.value = true
  try { recData.value = await api.govElementRecommend(recMetaId.value) } catch (e: any) { ElMessage.error(errMsg(e)) } finally { recLoading.value = false }
}
async function landFromRec(s: any, col: any) {
  if (!recData.value?.dsId) return ElMessage.warning('元数据缺数据源信息')
  const tableName = recData.value.tableName
  try {
    await api.govStdLand({ elementId: s.id, dsId: recData.value.dsId, tableName, columnName: col.column })
    ElMessage.success(`已落标：${col.column} → ${s.name}`)
  } catch (e: any) { ElMessage.error(errMsg(e)) }
}

onMounted(() => { loadEl(); loadCs() })
</script>
<style scoped>
.card-title { display: flex; align-items: center; justify-content: space-between; font-weight: 600; margin-bottom: 12px; }
.role-tag { font-size: 12px; color: var(--tech-text-muted); border: 1px solid var(--tech-panel-border); padding: 2px 8px; border-radius: 4px; }
.muted { color: var(--tech-text-muted); font-size: 12px; }
.filter-bar { display: flex; gap: 8px; margin-bottom: 10px; align-items: center; flex-wrap: wrap; }
.rec-item { display: flex; align-items: center; gap: 6px; padding: 2px 0; font-size: 12px; }
</style>
