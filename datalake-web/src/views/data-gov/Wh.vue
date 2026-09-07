<template>
  <div class="dl-card">
    <div class="card-title"><span>数据仓库 · 分层管理</span><span class="role-tag">系统管理员</span></div>
    <el-tabs v-model="tab">
      <el-tab-pane label="分层管理" name="layer">
        <div class="layer-layout">
          <!-- 左：分层树 -->
          <div class="layer-tree-pane">
            <div class="tree-head">
              <span class="th-t">数据分层</span>
              <el-button link size="small" type="primary" @click="openLayer()"><el-icon><Plus /></el-icon> 新增层级</el-button>
            </div>
            <el-tree ref="treeRef" :data="layerTree" node-key="code" highlight-current default-expand-all
              :expand-on-click-node="false" @node-click="onPickLayer">
              <template #default="{ data }">
                <div class="tree-node">
                  <span class="tn-label">
                    <el-icon class="tn-ic"><Folder /></el-icon>
                    <b v-if="data.code">{{ data.code }}</b>
                    <span class="tn-name">{{ data.name }}</span>
                  </span>
                  <span class="tn-right" @click.stop>
                    <span v-if="data.code" class="tn-badge" :class="{ zero: !bindCount(data.code) }" title="已绑定数据源数">{{ bindCount(data.code) }}</span>
                    <span v-if="data.code" class="tn-ops">
                      <el-icon title="编辑" @click.stop="openLayer(data)"><Edit /></el-icon>
                      <el-icon title="删除" class="tn-danger" @click.stop="delLayer(data)"><Delete /></el-icon>
                    </span>
                  </span>
                </div>
              </template>
            </el-tree>
            <div class="tree-hint muted">分层（ODS/DWD/DWS/ADS/DIM）绑定数据源后，数据探查/接入的「所属层级」即从此选取目标数据源。</div>
          </div>
          <!-- 右：绑定数据源列表 -->
          <div class="layer-main-pane">
            <div class="dl-toolbar">
              <el-input v-model="bindKw" placeholder="数据源名称" size="small" clearable style="width:160px" />
              <el-select v-model="bindType" placeholder="类型" size="small" clearable filterable style="width:120px">
                <el-option v-for="t in bindTypeOpts" :key="t" :label="t" :value="t" />
              </el-select>
              <el-select v-model="bindStatus" placeholder="状态" size="small" clearable style="width:110px">
                <el-option label="正常(NORMAL)" value="NORMAL" />
                <el-option label="停用(DISABLED)" value="DISABLED" />
              </el-select>
              <div class="toolbar-actions">
                <el-button size="small" @click="resetBindQuery">重置</el-button>
                <el-button size="small" type="primary" :disabled="!curLayer" @click="openBind">
                  <el-icon><Plus /></el-icon> 绑定数据源<span v-if="curLayer" class="bind-to">→ {{ curLayer }}</span>
                </el-button>
              </div>
            </div>
            <el-table :data="bindPaged" size="small" stripe border v-loading="loading">
              <el-table-column label="数据源" min-width="150">
                <template #default="{ row }">
                  <b>{{ row.ds_name || ('ds#' + row.datasource_id) }}</b>
                  <span class="muted ds-id">#{{ row.datasource_id }}</span>
                </template>
              </el-table-column>
              <el-table-column label="类型" width="100">
                <template #default="{ row }"><el-tag size="small" effect="plain">{{ row.ds_type || '—' }}</el-tag></template>
              </el-table-column>
              <el-table-column label="内部存储" width="80" align="center">
                <template #default="{ row }">
                  <el-tag v-if="isInternal(row.ds_type)" size="small" type="success">内部</el-tag>
                  <span v-else class="muted">外部</span>
                </template>
              </el-table-column>
              <el-table-column label="地址" min-width="160">
                <template #default="{ row }"><code class="addr">{{ row.host ? row.host + ':' + row.port : '—' }}</code>{{ row.db_name ? ' / ' + row.db_name : '' }}</template>
              </el-table-column>
              <el-table-column label="状态" width="84">
                <template #default="{ row }">
                  <el-tag size="small" :type="row.ds_status === 'NORMAL' ? 'success' : 'info'">{{ row.ds_status || '—' }}</el-tag>
                </template>
              </el-table-column>
              <el-table-column v-if="!curLayer" label="所属层" width="80">
                <template #default="{ row }"><el-tag size="small" type="warning" effect="plain">{{ row.layer_code }}</el-tag></template>
              </el-table-column>
              <el-table-column label="操作" width="80" fixed="right">
                <template #default="{ row }">
                  <el-button link size="small" type="danger" @click="unbind(row)">解绑</el-button>
                </template>
              </el-table-column>
            </el-table>
            <div class="empty-tip muted" v-if="!bindFiltered.length && !loading">{{ curLayer ? `层级 ${curLayer} 暂未绑定数据源，点右上「绑定数据源」添加` : '暂无绑定关系，左侧选择层级后绑定' }}</div>
            <div class="dl-pagination">
              <el-pagination :current-page="bindPage.page" :page-size="bindPage.size" :total="bindFiltered.length"
                :page-sizes="[10, 20, 50]" layout="total, sizes, prev, pager, next, jumper" size="small" background
                @size-change="onBindSizeChange" @current-change="onBindPageChange" />
            </div>
          </div>
        </div>
      </el-tab-pane>
      <el-tab-pane label="分层画像" name="stats">
        <!-- 顶部：占比图 + 行数分布 -->
        <div class="stats-charts" v-loading="loadingStats">
          <div class="chart-panel">
            <div class="cp-t"><el-icon><PieChart /></el-icon> 各层表数占比<span class="muted">共 {{ statTotalTables }} 张</span></div>
            <v-chart :option="pieOption" :theme="chartTheme" autoresize class="ch" />
          </div>
          <div class="chart-panel">
            <div class="cp-t"><el-icon><Histogram /></el-icon> 各层行数分布<span class="muted">共 {{ fmtNum(statTotalRows) }} 行</span></div>
            <v-chart :option="barOption" :theme="chartTheme" autoresize class="ch" />
          </div>
        </div>
        <!-- 画像卡片（点击钻取表清单） -->
        <el-row :gutter="10">
          <el-col v-for="s in stats" :key="s.code" :span="6" style="margin-bottom:10px">
            <div class="stat-card stat-click" @click="openLayerTables(s)">
              <div class="stat-head">
                <b>{{ s.code }}</b><span class="muted">{{ s.name }}</span>
                <el-tag v-if="!s.db_exists" size="small" type="danger" effect="plain">库未初始化</el-tag>
                <el-tag size="small" :type="s.source === 'physical' ? 'success' : 'info'" effect="plain">{{ s.source === 'physical' ? '实测' : '登记' }}</el-tag>
              </div>
              <div class="stat-row"><span>物理表</span><b>{{ s.tables }}<span class="unit">张</span></b></div>
              <div class="stat-row"><span>行数合计</span><b>{{ fmtNum(s.rows) }}</b></div>
              <div class="stat-row"><span>存储占用</span><b>{{ fmtSize(s.size_bytes) }}</b></div>
              <div class="stat-row"><span>绑定数据源</span><b>{{ s.ds_count ?? 0 }}<span class="unit">个</span></b></div>
              <div class="stat-row">
                <span>命名合规</span>
                <b>
                  <el-tag v-if="!s.naming_checked" size="small" type="info" effect="plain">未配置</el-tag>
                  <el-tag v-else-if="s.naming_violate === 0" size="small" type="success">{{ s.naming_checked }}/{{ s.naming_checked }}</el-tag>
                  <el-tag v-else size="small" type="warning">{{ s.naming_checked - s.naming_violate }}/{{ s.naming_checked }}</el-tag>
                </b>
              </div>
              <div class="stat-foot">
                <span class="muted">最近更新 {{ fmtShort(s.last_update) }}</span>
                <span class="foot-right">
                  <el-button v-if="!s.db_exists" link size="small" type="warning" @click.stop="initLayerDb(s)">初始化库</el-button>
                  <span class="link">表清单 →</span>
                </span>
              </div>
            </div>
          </el-col>
        </el-row>
        <!-- 近30天容量趋势（每日快照） -->
        <div class="chart-panel" v-loading="loadingTrend">
          <div class="cp-t">
            <el-icon><TrendCharts /></el-icon> 近30天各层行数趋势
            <span class="muted">每日 01:37 自动快照（历史 {{ trendDates.length }} 天）</span>
            <el-button size="small" style="margin-left:auto" :loading="snapshotting" @click="snapshotNow">立即快照</el-button>
          </div>
          <v-chart :option="trendOption" :theme="chartTheme" autoresize class="ch" style="height:230px" />
          <div v-if="!trendDates.length" class="empty-tip muted">暂无历史快照，点右上「立即快照」记录今日基线</div>
        </div>
        <div class="hint"><el-icon><InfoFilled /></el-icon> 点击卡片查看层内表清单；存储/行数为 StarRocks information_schema 实测，命名合规来自命名巡检规则，绑定数与分层管理共享；「库未初始化」= 该层绑定目标上还没有层编码库，可点「初始化库」按绑定路由创建。</div>
      </el-tab-pane>
      <el-tab-pane label="命名巡检" name="naming">
        <div class="dl-toolbar" style="padding:0;margin-bottom:10px">
          <el-button size="small" type="primary" :loading="loadingNaming" @click="runNamingCheck">立即巡检</el-button>
          <span class="muted">扫描 = 元数据登记表 ∪ 主库物理表直扫 · 每日 02:43 自动巡检，违规落库并告警 · 派单走质量工单中心</span>
          <div class="toolbar-actions"><span v-if="namingRuns.length" class="muted">上次 {{ fmtTime(namingRuns[0].run_time) }}</span></div>
        </div>
        <template v-if="naming">
          <el-alert v-if="naming.checked === 0" title="没有可巡检的表（需元数据登记表或层编码库有物理表，且分层配置了命名规范）" type="info" :closable="false" />
          <el-alert v-else-if="naming.violate === 0" :title="`巡检通过：${naming.checked} 张表全部符合分层命名规范`" type="success" :closable="false" style="margin-bottom:10px" />
          <el-alert v-else :title="`发现 ${naming.violate}/${naming.checked} 张表命名不规范`" type="warning" :closable="false" style="margin-bottom:10px" />
          <el-table v-if="naming.violations?.length" :data="namingPaged" size="small" border max-height="420">
            <el-table-column prop="layer" label="层" width="70" />
            <el-table-column prop="table" label="表名" min-width="170"><template #default="{ row }"><code>{{ row.table }}</code></template></el-table-column>
            <el-table-column prop="pattern" label="命名规范" width="110" />
            <el-table-column prop="suggest" label="建议表名" min-width="160"><template #default="{ row }"><span class="suggest">{{ row.suggest }}</span></template></el-table-column>
            <el-table-column label="状态" width="130">
              <template #default="{ row }">
                <el-tag v-if="row.status === 'TICKETED'" size="small" type="warning">已派单 #{{ row.ticket_id }}</el-tag>
                <el-tag v-else size="small" type="danger">待处理</el-tag>
              </template>
            </el-table-column>
            <el-table-column label="发现时间" width="150">
              <template #default="{ row }">
                <div class="muted">首 {{ fmtShort(row.first_found) }}</div>
                <div class="muted">近 {{ fmtShort(row.last_seen) }}</div>
              </template>
            </el-table-column>
            <el-table-column label="操作" width="220" fixed="right">
              <template #default="{ row }">
                <div class="row-actions">
                  <el-button size="small" link type="primary" :disabled="row.status === 'TICKETED'" @click="openAssign(row)">派单</el-button>
                  <el-button size="small" link type="primary" @click="copyFixSql(row)">复制SQL</el-button>
                  <el-button size="small" link type="success" @click="markResolved(row)">已整改</el-button>
                  <el-button size="small" link @click="ignoreIssue(row)">忽略</el-button>
                </div>
              </template>
            </el-table-column>
          </el-table>
          <div v-else-if="naming.checked > 0" class="empty-tip muted">无未结违规（已整改/已忽略的不在列表，改名后复核自动关闭）</div>
          <div class="dl-pagination" v-if="naming.violations?.length">
            <el-pagination :current-page="namingPage.page" :page-size="namingPage.size" :total="naming.violations.length"
              :page-sizes="[10, 20, 50]" layout="total, sizes, prev, pager, next, jumper" size="small" background
              @size-change="onNamingSizeChange" @current-change="onNamingPageChange" />
          </div>
        </template>
        <div v-if="namingRuns.length > 1" class="chart-panel" style="margin-top:12px">
          <div class="cp-t"><el-icon><TrendCharts /></el-icon> 巡检历史<span class="muted">巡检表数 / 违规数 趋势</span></div>
          <v-chart :option="namingRunsOption" :theme="chartTheme" autoresize class="ch" style="height:200px" />
        </div>
      </el-tab-pane>
      <el-tab-pane label="主题域" name="subject">
        <!-- 主题域画像统计 -->
        <el-row :gutter="10" style="margin-bottom:10px">
          <el-col :span="6"><div class="stat-card"><div class="stat-head"><b>{{ subjectStat.roots }}</b><span class="muted">根主题域</span></div></div></el-col>
          <el-col :span="6"><div class="stat-card"><div class="stat-head"><b>{{ subjectStat.children }}</b><span class="muted">子域</span></div></div></el-col>
          <el-col :span="6"><div class="stat-card"><div class="stat-head"><b>{{ subjectStat.models }}</b><span class="muted">挂载模型</span></div></div></el-col>
          <el-col :span="6"><div class="stat-card"><div class="stat-head"><b>{{ subjectStat.assets }}</b><span class="muted">挂载资产</span></div></div></el-col>
        </el-row>
        <div style="margin-bottom:10px">
          <el-button type="primary" size="small" @click="openSubject()"><el-icon><Plus /></el-icon> 新增主题域</el-button>
          <span class="muted" style="margin-left:8px">主题域在 数据模型（domain）/ 元数据补录（subject_id）/ 数据资产（subject_id）三处统一引用</span>
        </div>
        <el-table :data="subjectPaged" row-key="id" size="small" border default-expand-all>
          <el-table-column prop="code" label="编码" width="130" />
          <el-table-column prop="name" label="名称" min-width="150" />
          <el-table-column label="模型数" width="80" align="center"><template #default="{ row }">{{ row.model_count ?? 0 }}</template></el-table-column>
          <el-table-column label="资产数" width="80" align="center"><template #default="{ row }">{{ row.asset_count ?? 0 }}</template></el-table-column>
          <el-table-column label="子域数" width="80" align="center"><template #default="{ row }">{{ row.child_count ?? 0 }}</template></el-table-column>
          <el-table-column prop="sort" label="排序" width="70" />
          <el-table-column label="操作" width="150">
            <template #default="{ row }">
              <el-button link size="small" type="primary" @click="openSubject(row, null)">编辑</el-button>
              <el-button link size="small" type="primary" @click="openSubject(null, row)">加子域</el-button>
              <el-button link size="small" type="danger" @click="delSubject(row)">删除</el-button>
            </template>
          </el-table-column>
        </el-table>
        <div class="dl-pagination">
          <el-pagination :current-page="subjectPage.page" :page-size="subjectPage.size" :total="subjects.length"
            :page-sizes="[10, 20, 50]" layout="total, sizes, prev, pager, next, jumper" size="small" background
            @size-change="onSubjectSizeChange" @current-change="onSubjectPageChange" />
        </div>
      </el-tab-pane>
    </el-tabs>

    <!-- 层级编辑 -->
    <el-drawer v-model="layerDlg" :title="layerForm.code ? '编辑层级' : '新增层级'" size="560px">
      <el-form :model="layerForm" label-width="80px" size="small">
        <el-form-item label="编码"><el-input v-model="layerForm.code" :disabled="!!layerForm.code" placeholder="如 dwd" /></el-form-item>
        <el-form-item label="名称"><el-input v-model="layerForm.name" /></el-form-item>
        <el-form-item label="排序"><el-input-number v-model="layerForm.sort" :min="0" /></el-form-item>
        <el-form-item label="命名规范"><el-input v-model="layerForm.naming_pattern" placeholder="^dwd_" /></el-form-item>
      </el-form>
      <template #footer><el-button @click="layerDlg = false">取消</el-button><el-button type="primary" @click="saveLayer">保存</el-button></template>
    </el-drawer>

    <!-- 绑定数据源（多选抽屉） -->
    <el-drawer v-model="bindDlg" :title="`绑定数据源 → 层级 ${curLayer}`" size="760px" destroy-on-close>
      <div class="dl-toolbar" style="padding:0;margin-bottom:10px">
        <el-input v-model="bindPickKw" placeholder="名称/类型检索" size="small" clearable style="width:200px" />
        <span class="muted">已绑定 {{ boundIds.length }} 个，可选 {{ bindCandidates.length }} 个</span>
      </div>
      <el-table ref="bindPickRef" :data="bindCandidates" row-key="id" size="small" border max-height="460"
        @selection-change="onBindSelChange">
        <el-table-column type="selection" width="42" :selectable="canPickDs" />
        <el-table-column label="数据源" min-width="150">
          <template #default="{ row }"><b>{{ row.name }}</b><span class="muted ds-id">#{{ row.id }}</span></template>
        </el-table-column>
        <el-table-column label="类型" width="100">
          <template #default="{ row }"><el-tag size="small" effect="plain">{{ row.type }}</el-tag></template>
        </el-table-column>
        <el-table-column label="地址" min-width="150">
          <template #default="{ row }"><code class="addr">{{ row.host }}:{{ row.port }}</code> / {{ row.db_name }}</template>
        </el-table-column>
        <el-table-column label="状态" width="130">
          <template #default="{ row }">
            <el-tag v-if="boundIds.includes(row.id)" size="small" type="info">已绑定</el-tag>
            <el-tag v-else size="small" :type="row.status === 'NORMAL' ? 'success' : 'info'">{{ row.status }}</el-tag>
          </template>
        </el-table-column>
      </el-table>
      <template #footer>
        <span class="muted" style="margin-right:12px">已勾选 {{ bindSel.length }} 个</span>
        <el-button @click="bindDlg = false">取消</el-button>
        <el-button type="primary" :loading="binding" :disabled="!bindSel.length" @click="doBind">绑定</el-button>
      </template>
    </el-drawer>

    <!-- 层内表清单（画像钻取） -->
    <el-drawer v-model="ltDlg" :title="`表清单 - ${ltLayer?.code || ''} 层（${ltLayer?.name || ''}）`" size="860px" destroy-on-close>
      <div class="dl-toolbar" style="padding:0;margin-bottom:10px">
        <el-input v-model="ltKw" placeholder="表名检索" size="small" clearable style="width:200px" />
        <span class="muted">共 {{ ltFiltered.length }} 张表 · {{ fmtNum(ltTotalRows) }} 行</span>
      </div>
      <el-table :data="ltFiltered" size="small" stripe border v-loading="ltLoading" max-height="560">
        <el-table-column label="表名" min-width="200">
          <template #default="{ row }"><code class="lt-name">{{ row.name }}</code></template>
        </el-table-column>
        <el-table-column label="行数" width="100" align="right">
          <template #default="{ row }"><b>{{ fmtNum(row.rows_cnt) }}</b></template>
        </el-table-column>
        <el-table-column label="大小" width="90" align="right">
          <template #default="{ row }">{{ fmtSize(row.size_bytes) }}</template>
        </el-table-column>
        <el-table-column prop="comment" label="注释" min-width="140" show-overflow-tooltip>
          <template #default="{ row }"><span v-if="row.comment">{{ row.comment }}</span><span v-else class="muted">—</span></template>
        </el-table-column>
        <el-table-column label="最近更新" width="150">
          <template #default="{ row }">{{ fmtTime(row.last_update) }}</template>
        </el-table-column>
        <el-table-column label="元数据采集" width="150">
          <template #default="{ row }">{{ fmtTime(row.synced_time) }}</template>
        </el-table-column>
      </el-table>
      <div v-if="!ltLoading && !ltFiltered.length" class="empty-tip muted">该层暂无物理表</div>
    </el-drawer>

    <!-- 主题域编辑 -->
    <el-drawer v-model="subjectDlg" :title="subjectForm.id ? '编辑主题域' : '新增主题域'" size="560px">
      <el-form :model="subjectForm" label-width="80px" size="small">
        <el-form-item label="编码"><el-input v-model="subjectForm.code" placeholder="trade" /></el-form-item>
        <el-form-item label="名称"><el-input v-model="subjectForm.name" placeholder="交易域" /></el-form-item>
        <el-form-item label="父节点"><el-tree-select v-model="subjectForm.parent_id" :data="subjectTreeData" node-key="id" check-strictly :render-after-expand="false" style="width:100%" placeholder="无（根节点）" clearable /></el-form-item>
        <el-form-item label="排序"><el-input-number v-model="subjectForm.sort" :min="0" /></el-form-item>
      </el-form>
      <template #footer><el-button @click="subjectDlg = false">取消</el-button><el-button type="primary" @click="saveSubject">保存</el-button></template>
    </el-drawer>

    <!-- 命名违规派单（复用质量工单中心） -->
    <el-dialog v-model="assignDlg" title="命名违规派单" width="480px">
      <div class="muted" style="margin-bottom:10px" v-if="assignRow">
        <code>{{ assignRow.layer }}.{{ assignRow.table }}</code> → 建议 <span class="suggest">{{ assignRow.suggest }}</span>
      </div>
      <el-form :model="assignForm" label-width="80px" size="small">
        <el-form-item label="处理人">
          <el-select v-model="assignForm.assignee" filterable allow-create default-first-option placeholder="选择或输入处理人账号" style="width:100%">
            <el-option v-for="u in issueUsers" :key="u.username" :label="`${u.name}（${u.username}）`" :value="u.username" />
          </el-select>
        </el-form-item>
        <el-form-item label="截止时间">
          <el-date-picker v-model="assignForm.deadline" type="datetime" value-format="YYYY-MM-DD HH:mm:ss" placeholder="SLA 截止（选填）" style="width:100%" />
        </el-form-item>
        <el-form-item label="严重度">
          <el-select v-model="assignForm.severity" style="width:100%">
            <el-option label="严重(CRITICAL)" value="CRITICAL" />
            <el-option label="主要(MAJOR)" value="MAJOR" />
            <el-option label="次要(MINOR)" value="MINOR" />
          </el-select>
        </el-form-item>
      </el-form>
      <div class="muted" style="line-height:1.7">工单进入「运维中心 → 工单中心」完整生命周期（处理/复核/关闭 + SLA 超期提醒）；整改 SQL（RENAME 语句）随工单样例保存，可导出核对。</div>
      <template #footer><el-button @click="assignDlg = false">取消</el-button><el-button type="primary" :loading="assigning" @click="doAssign">派单</el-button></template>
    </el-dialog>
  </div>
</template>

<script setup lang="ts">
import { computed, nextTick, onMounted, reactive, ref } from 'vue'
import { ElMessage, ElMessageBox } from 'element-plus'
import { Plus, Folder, Edit, Delete, PieChart, Histogram, InfoFilled, TrendCharts } from '@element-plus/icons-vue'
import { api, errMsg } from '@/api'
import { VChart } from '@/echarts'
import { theme } from '@/theme'
// 顶层持有 computed，模板里自动解包为字符串；直接内联 theme.chartTheme 传的是 ComputedRefImpl 对象，
// echarts 深克隆 ref 内部循环引用图会爆栈（RangeError: Maximum call stack size exceeded）
const chartTheme = theme.chartTheme

const tab = ref('layer')
const loading = ref(false)
const layers = ref<any[]>([])
const dsList = ref<any[]>([])
// 内部存储型数据源（可作数仓存储），与后端 DataSourceController.INTERNAL_TYPES 对齐
const INTERNAL_TYPES = new Set(['mysql', 'starrocks', 'doris', 'clickhouse', 'hive', 'iceberg'])

// ===== 左树：分层 =====
const treeRef = ref<any>()
const curLayer = ref('')   // ''=全部层级
const layerTree = computed(() => [
  { code: '', name: '全部层级', children: layers.value.map((l: any) => ({ ...l, children: undefined })) }
])
function onPickLayer(data: any) { curLayer.value = data.code || ''; bindPage.page = 1 }
function isInternal(t?: string) { return !!t && INTERNAL_TYPES.has(t) }

// ===== 绑定关系（一接口全拉，富化行） =====
const bindRows = ref<any[]>([])
const bindCountMap = computed<Record<string, number>>(() => {
  const m: Record<string, number> = {}
  bindRows.value.forEach(b => { m[b.layer_code] = (m[b.layer_code] || 0) + 1 })
  return m
})
function bindCount(code: string) { return bindCountMap.value[code] || 0 }

// 右表筛选 + 客户端分页
const bindKw = ref(''); const bindType = ref(''); const bindStatus = ref('')
const bindPage = reactive({ page: 1, size: 10 })
const bindTypeOpts = computed(() => [...new Set(bindRows.value.map((b: any) => b.ds_type).filter(Boolean))].sort())
const bindFiltered = computed(() => {
  let rows = curLayer.value ? bindRows.value.filter(b => b.layer_code === curLayer.value) : bindRows.value
  if (bindKw.value) {
    const k = bindKw.value.toLowerCase()
    rows = rows.filter((b: any) => (b.ds_name || '').toLowerCase().includes(k) || String(b.datasource_id).includes(k))
  }
  if (bindType.value) rows = rows.filter((b: any) => b.ds_type === bindType.value)
  if (bindStatus.value) rows = rows.filter((b: any) => b.ds_status === bindStatus.value)
  return rows
})
const bindPaged = computed(() => bindFiltered.value.slice((bindPage.page - 1) * bindPage.size, bindPage.page * bindPage.size))
function resetBindQuery() { bindKw.value = ''; bindType.value = ''; bindStatus.value = ''; bindPage.page = 1 }
function onBindSizeChange(s: number) { bindPage.size = s; bindPage.page = 1 }
function onBindPageChange(p: number) { bindPage.page = p }

async function load() {
  loading.value = true
  try {
    const [ls, ds, binds] = await Promise.all([api.govLayers(), api.daSources(), api.govLayerDs()])
    layers.value = ls; dsList.value = ds; bindRows.value = binds || []
    await nextTick()
    treeRef.value?.setCurrentKey(curLayer.value)
  } catch (e: any) { ElMessage.error(errMsg(e)) } finally { loading.value = false }
}

// ===== 层级 CRUD =====
const layerDlg = ref(false); const layerForm = reactive<any>({ code: '', name: '', sort: 1, naming_pattern: '' })
function openLayer(row?: any) { Object.assign(layerForm, { code: '', name: '', sort: 1, naming_pattern: '' }, row || {}); layerDlg.value = true }
async function saveLayer() {
  try { await api.govSaveLayer({ ...layerForm }); ElMessage.success('保存成功'); layerDlg.value = false; await load() }
  catch (e: any) { ElMessage.error(errMsg(e)) }
}
async function delLayer(row: any) {
  try { await ElMessageBox.confirm(`删除层级 ${row.code}？其 ${bindCount(row.code)} 条数据源绑定将一并解除`, '提示', { type: 'warning' }) } catch { return }
  try { await api.govDeleteLayer(row.code); ElMessage.success('已删除'); if (curLayer.value === row.code) curLayer.value = ''; await load() }
  catch (e: any) { ElMessage.error(errMsg(e)) }
}

// ===== 绑定 / 解绑 =====
const bindDlg = ref(false); const bindPickKw = ref(''); const bindSel = ref<any[]>([]); const binding = ref(false)
const boundIds = computed(() => bindRows.value.filter(b => b.layer_code === curLayer.value).map(b => b.datasource_id))
const bindCandidates = computed(() => {
  if (!bindPickKw.value) return dsList.value
  const k = bindPickKw.value.toLowerCase()
  return dsList.value.filter((d: any) => (d.name || '').toLowerCase().includes(k) || (d.type || '').toLowerCase().includes(k))
})
function onBindSelChange(sel: any[]) { bindSel.value = sel }
function canPickDs(row: any) { return !boundIds.value.includes(row.id) }
function openBind() { if (!curLayer.value) return ElMessage.warning('请先在左侧选择具体层级'); bindPickKw.value = ''; bindSel.value = []; bindDlg.value = true }
async function doBind() {
  binding.value = true
  try {
    const r: any = await api.govBindLayerDs({ layer_code: curLayer.value, datasource_ids: bindSel.value.map((s: any) => s.id) })
    ElMessage.success(`已绑定 ${r.added ?? bindSel.value.length} 个数据源`)
    bindDlg.value = false; await load()
  } catch (e: any) { ElMessage.error(errMsg(e)) } finally { binding.value = false }
}
async function unbind(row: any) {
  try { await ElMessageBox.confirm(`解除层级 ${row.layer_code} 与数据源「${row.ds_name || row.datasource_id}」的绑定？`, '提示', { type: 'warning' }) } catch { return }
  try { await api.govUnbindLayerDs(row.id); ElMessage.success('已解绑'); await load() } catch (e: any) { ElMessage.error(errMsg(e)) }
}

// ===== 分层画像 / 命名巡检 =====
const stats = ref<any[]>([]); const loadingStats = ref(false)
const naming = ref<any>(null); const loadingNaming = ref(false)
function fmtNum(n: any) { const v = Number(n) || 0; return v >= 10000000 ? (v / 10000000).toFixed(1) + ' 千万' : v >= 10000 ? (v / 10000).toFixed(1) + ' 万' : String(v) }
function fmtSize(n: any) {
  const v = Number(n) || 0
  if (!v) return '—'
  if (v >= 1073741824) return (v / 1073741824).toFixed(1) + ' GB'
  if (v >= 1048576) return (v / 1048576).toFixed(1) + ' MB'
  if (v >= 1024) return (v / 1024).toFixed(1) + ' KB'
  return v + ' B'
}
function fmtShort(s?: string) { if (!s) return '—'; const t = String(s).replace('T', ' '); return t.length >= 16 ? t.slice(5, 16) : t }
function fmtTime(s?: string) { return s ? String(s).replace('T', ' ').slice(0, 19) : '—' }

const statTotalTables = computed(() => stats.value.reduce((s, x) => s + Number(x.tables || 0), 0))
const statTotalRows = computed(() => stats.value.reduce((s, x) => s + Number(x.rows || 0), 0))
const pieOption = computed(() => ({
  tooltip: { trigger: 'item', formatter: '{b}: {c} 张 ({d}%)' },
  legend: { bottom: 0, icon: 'circle', itemWidth: 8, itemHeight: 8 },
  series: [{
    type: 'pie', radius: ['42%', '68%'], center: ['50%', '44%'],
    itemStyle: { borderRadius: 4, borderColor: 'transparent', borderWidth: 2 },
    label: { formatter: '{b}\n{c} 张' },
    data: stats.value.map((s: any) => ({ name: s.code, value: Number(s.tables || 0) }))
  }]
}))
const barOption = computed(() => ({
  tooltip: { trigger: 'axis', axisPointer: { type: 'shadow' }, valueFormatter: (v: number) => fmtNum(v) },
  grid: { left: 8, right: 18, top: 14, bottom: 6, containLabel: true },
  xAxis: { type: 'value', axisLabel: { formatter: (v: number) => fmtNum(v) } },
  yAxis: { type: 'category', data: stats.value.map((s: any) => s.code).reverse(), axisTick: { show: false } },
  series: [{
    type: 'bar', barWidth: 14, itemStyle: { borderRadius: [0, 4, 4, 0] },
    label: { show: true, position: 'right', formatter: (p: any) => fmtNum(p.value), fontSize: 11 },
    data: stats.value.map((s: any) => Number(s.rows || 0)).reverse()
  }]
}))
async function loadStats() { loadingStats.value = true; try { stats.value = await api.govLayerStats() } catch (e: any) { ElMessage.error(errMsg(e)) } finally { loadingStats.value = false } }
async function runNamingCheck() {
  loadingNaming.value = true
  try { naming.value = await api.govLayerNamingCheck(); namingPage.page = 1; await loadNamingRuns() }
  catch (e: any) { ElMessage.error(errMsg(e)) } finally { loadingNaming.value = false }
}

// ===== 容量趋势（每日快照） + 层库初始化 =====
const statsHistory = ref<any[]>([]); const loadingTrend = ref(false); const snapshotting = ref(false)
async function loadTrend() { loadingTrend.value = true; try { statsHistory.value = await api.govLayerStatsHistory(30) } catch { statsHistory.value = [] } finally { loadingTrend.value = false } }
const trendDates = computed(() => [...new Set(statsHistory.value.map((r: any) => String(r.snap_date).slice(0, 10)))].sort())
const trendOption = computed(() => {
  const dates = trendDates.value
  const layers = [...new Set(statsHistory.value.map((r: any) => r.layer_code))]
  const series = layers.map((lc: string) => {
    const m = new Map(statsHistory.value.filter((r: any) => r.layer_code === lc).map((r: any) => [String(r.snap_date).slice(0, 10), r]))
    return { name: lc, type: 'line', smooth: true, showSymbol: false, connectNulls: true, data: dates.map((d: string) => (m.has(d) ? Number(m.get(d).rows_cnt || 0) : null)) }
  })
  return {
    tooltip: { trigger: 'axis', valueFormatter: (v: number) => fmtNum(v) },
    legend: { bottom: 0, icon: 'circle', itemWidth: 8, itemHeight: 8 },
    grid: { left: 8, right: 18, top: 16, bottom: 36, containLabel: true },
    xAxis: { type: 'category', data: dates, boundaryGap: false },
    yAxis: { type: 'value', axisLabel: { formatter: (v: number) => fmtNum(v) }, splitLine: { lineStyle: { type: 'dashed' } } },
    series,
  }
})
async function snapshotNow() {
  snapshotting.value = true
  try { const r: any = await api.govLayerStatsSnapshot(); ElMessage.success(`已记录 ${r.layers ?? 0} 层当日快照`); await Promise.all([loadTrend(), loadStats()]) }
  catch (e: any) { ElMessage.error(errMsg(e)) } finally { snapshotting.value = false }
}
async function initLayerDb(s: any) {
  try { await ElMessageBox.confirm(`在 ${s.code} 层的绑定目标上初始化「${s.code}」库？（未绑定数据源 → 主库 StarRocks）`, '初始化层库', { type: 'info' }) } catch { return }
  try { const r: any = await api.govLayerInitDb(s.code); ElMessage.success(`已初始化：${r.target}`); await loadStats() }
  catch (e: any) { ElMessage.error(errMsg(e)) }
}

// ===== 命名巡检闭环：分页 / 派单 / 复制SQL / 整改 / 忽略 + 巡检历史 =====
const namingRuns = ref<any[]>([])
const issueUsers = ref<any[]>([])   // {username, name} 对象数组，同 Ticket.vue 派单下拉
const namingPage = reactive({ page: 1, size: 10 })
const namingPaged = computed(() => (naming.value?.violations || []).slice((namingPage.page - 1) * namingPage.size, namingPage.page * namingPage.size))
function onNamingSizeChange(s: number) { namingPage.size = s; namingPage.page = 1 }
function onNamingPageChange(p: number) { namingPage.page = p }
async function loadNamingRuns() { try { namingRuns.value = await api.govLayerNamingRuns(30) } catch { namingRuns.value = [] } }
async function loadIssueUsers() { try { issueUsers.value = await api.govQualityIssueUsers() } catch { issueUsers.value = [] } }
const namingRunsOption = computed(() => {
  const runs = [...namingRuns.value].reverse()
  return {
    tooltip: { trigger: 'axis' },
    legend: { bottom: 0, icon: 'circle', itemWidth: 8, itemHeight: 8 },
    grid: { left: 8, right: 18, top: 14, bottom: 36, containLabel: true },
    xAxis: { type: 'category', data: runs.map((r: any) => fmtShort(r.run_time)) },
    yAxis: { type: 'value', minInterval: 1, splitLine: { lineStyle: { type: 'dashed' } } },
    series: [
      { name: '巡检表数', type: 'line', smooth: true, showSymbol: false, data: runs.map((r: any) => Number(r.checked || 0)) },
      { name: '违规数', type: 'line', smooth: true, showSymbol: false, areaStyle: { opacity: 0.12 }, data: runs.map((r: any) => Number(r.violate || 0)) },
    ],
  }
})
const assignDlg = ref(false); const assignRow = ref<any>(null); const assigning = ref(false)
const assignForm = reactive<any>({ assignee: '', deadline: '', severity: 'MAJOR' })
function openAssign(row: any) { assignRow.value = row; Object.assign(assignForm, { assignee: '', deadline: '', severity: 'MAJOR' }); assignDlg.value = true }
async function doAssign() {
  if (!assignForm.assignee) return ElMessage.warning('请选择处理人')
  assigning.value = true
  try {
    const r: any = await api.govNamingAssign(assignRow.value.issue_id, assignForm.assignee, assignForm.deadline || undefined, assignForm.severity)
    ElMessage.success(`已派单 #${r.ticketId}，到「运维中心 → 工单中心」跟踪处理`)
    assignDlg.value = false; await runNamingCheck()
  } catch (e: any) { ElMessage.error(errMsg(e)) } finally { assigning.value = false }
}
function copyFixSql(row: any) {
  const sql = 'ALTER TABLE `' + row.layer + '`.`' + row.table + '` RENAME TO `' + row.suggest + '`;'
  navigator.clipboard.writeText(sql).then(() => ElMessage.success('已复制整改 SQL'), () => ElMessage.warning('复制失败，请手动复制'))
}
async function markResolved(row: any) {
  try { await ElMessageBox.confirm(`标记 ${row.layer}.${row.table} 已整改？（下轮巡检未再见到即自动关闭）`, '提示', { type: 'success' }) } catch { return }
  try { await api.govNamingStatus(row.issue_id, 'RESOLVED'); ElMessage.success('已标记整改'); await runNamingCheck() } catch (e: any) { ElMessage.error(errMsg(e)) }
}
async function ignoreIssue(row: any) {
  try { await ElMessageBox.confirm(`忽略 ${row.layer}.${row.table} 的命名违规？（后续巡检不再计入违规）`, '提示', { type: 'warning' }) } catch { return }
  try { await api.govNamingStatus(row.issue_id, 'IGNORED'); ElMessage.success('已忽略'); await runNamingCheck() } catch (e: any) { ElMessage.error(errMsg(e)) }
}

// ===== 层内表清单（画像钻取） =====
const ltDlg = ref(false); const ltLoading = ref(false)
const ltLayer = ref<any>(null); const ltRows = ref<any[]>([]); const ltKw = ref('')
const ltFiltered = computed(() => {
  if (!ltKw.value) return ltRows.value
  const k = ltKw.value.toLowerCase()
  return ltRows.value.filter((r: any) => (r.name || '').toLowerCase().includes(k) || (r.comment || '').includes(ltKw.value))
})
const ltTotalRows = computed(() => ltFiltered.value.reduce((s, r) => s + Number(r.rows_cnt || 0), 0))
async function openLayerTables(s: any) {
  ltLayer.value = s; ltKw.value = ''; ltRows.value = []; ltDlg.value = true; ltLoading.value = true
  try { ltRows.value = await api.govLayerTables(s.code) } catch (e: any) { ElMessage.error(errMsg(e)) } finally { ltLoading.value = false }
}

// ===== 主题域 =====
const subjects = ref<any[]>([])
const subjectDlg = ref(false); const subjectForm = reactive<any>({ id: null, code: '', name: '', parent_id: 0, sort: 1 })
const subjectTreeData = computed(() => subjects.value.map((s: any) => ({ ...s, value: s.id, label: s.code + ' / ' + s.name })))
const subjectPage = reactive({ page: 1, size: 10 })
const subjectPaged = computed(() => subjects.value.slice((subjectPage.page - 1) * subjectPage.size, subjectPage.page * subjectPage.size))
// 主题域画像统计（根/子域/模型/资产，父域含子域并入）
const subjectStat = computed(() => {
  let roots = 0, children = 0, models = 0, assets = 0
  for (const s of subjects.value) {
    roots++; models += Number(s.model_count || 0); assets += Number(s.asset_count || 0)
    children += (s.children || []).length
  }
  return { roots, children, models, assets }
})
function onSubjectSizeChange(s: number) { subjectPage.size = s; subjectPage.page = 1 }
function onSubjectPageChange(p: number) { subjectPage.page = p }
async function loadSubjects() { try { subjects.value = await api.govSubjects() } catch { subjects.value = [] } }
function openSubject(row?: any, parent?: any) {
  Object.assign(subjectForm, { id: null, code: '', name: '', parent_id: 0, sort: 1 })
  if (row) Object.assign(subjectForm, { id: row.id, code: row.code, name: row.name, parent_id: row.parent_id || 0, sort: row.sort })
  if (parent) subjectForm.parent_id = parent.id
  subjectDlg.value = true
}
async function saveSubject() {
  if (!subjectForm.code || !subjectForm.name) return ElMessage.warning('填编码与名称')
  try { await api.govSaveSubject({ ...subjectForm }); ElMessage.success('保存成功'); subjectDlg.value = false; await loadSubjects() } catch (e: any) { ElMessage.error(errMsg(e)) }
}
async function delSubject(row: any) {
  await ElMessageBox.confirm(`删除主题域 ${row.code}？`, '提示', { type: 'warning' })
  try { await api.govDeleteSubject(row.id); ElMessage.success('已删除'); await loadSubjects() } catch (e: any) { ElMessage.error(errMsg(e)) }
}

onMounted(() => { load(); loadStats(); runNamingCheck(); loadSubjects(); loadTrend(); loadIssueUsers() })
</script>
<style scoped>
.card-title { display: flex; align-items: center; justify-content: space-between; font-weight: 600; margin-bottom: 12px; }
.role-tag { font-size: 12px; color: var(--tech-text-muted); border: 1px solid var(--tech-panel-border); padding: 2px 8px; border-radius: 4px; }
.muted { color: var(--tech-text-muted); font-size: 12px; }

/* 左树右表布局 */
.layer-layout { display: flex; gap: 14px; min-height: 420px; }
.layer-tree-pane { width: 250px; flex-shrink: 0; border: 1px solid var(--tech-panel-border); border-radius: 8px; padding: 10px; }
.tree-head { display: flex; align-items: center; justify-content: space-between; margin-bottom: 8px; padding: 0 2px; }
.th-t { font-weight: 600; }
.tree-hint { margin-top: 10px; padding-top: 8px; border-top: 1px dashed var(--tech-panel-border); line-height: 1.6; }
.layer-main-pane { flex: 1; min-width: 0; }

/* 树节点：label + 绑定数 badge + hover 操作 */
.tree-node { flex: 1; display: flex; align-items: center; justify-content: space-between; min-width: 0; padding-right: 4px; }
.tn-label { display: inline-flex; align-items: center; gap: 5px; min-width: 0; overflow: hidden; }
.tn-ic { color: var(--tech-primary); flex-shrink: 0; }
.tn-label b { flex-shrink: 0; }
.tn-name { overflow: hidden; text-overflow: ellipsis; white-space: nowrap; font-size: 12.5px; color: var(--tech-text-muted); }
.tn-right { display: inline-flex; align-items: center; gap: 5px; }
.tn-badge { min-width: 18px; text-align: center; font-size: 11px; border-radius: 9px; padding: 1px 5px; background: color-mix(in srgb, var(--tech-primary) 16%, transparent); color: var(--tech-primary); font-weight: 600; }
.tn-badge.zero { background: transparent; color: var(--tech-text-muted); border: 1px dashed var(--tech-panel-border); font-weight: 400; }
.tn-ops { display: none; gap: 4px; }
.tree-node:hover .tn-ops { display: inline-flex; }
.tn-ops .el-icon { font-size: 13px; cursor: pointer; color: var(--tech-text-muted); }
.tn-ops .el-icon:hover { color: var(--tech-primary); }
.tn-ops .tn-danger:hover { color: var(--tech-danger); }

/* 右表 */
.ds-id { margin-left: 6px; }
.addr { font-size: 12px; }
.bind-to { margin-left: 4px; }
.empty-tip { padding: 18px 8px; text-align: center; }

.stat-card { border: 1px solid var(--tech-panel-border); border-radius: 6px; padding: 12px 14px; }
.stat-head { display: flex; align-items: center; gap: 8px; margin-bottom: 8px; }
.stat-head b { font-size: 15px; }
.stat-head .el-tag { margin-left: auto; }
.stat-row { display: flex; justify-content: space-between; align-items: center; font-size: 13px; line-height: 24px; }
.unit { font-size: 11px; color: var(--tech-text-muted); margin-left: 2px; font-weight: 400; }
.stat-src { margin-top: 6px; font-size: 11px; }

/* 画像：图表行 + 可点击卡片 */
.stats-charts { display: flex; gap: 10px; margin-bottom: 12px; }
.chart-panel { flex: 1; min-width: 0; border: 1px solid var(--tech-panel-border); border-radius: 6px; padding: 10px 12px; }
.cp-t { display: flex; align-items: center; gap: 6px; font-weight: 600; margin-bottom: 4px; }
.cp-t .el-icon { color: var(--tech-primary); }
.cp-t .muted { margin-left: auto; font-weight: 400; }
.chart-panel .ch { height: 210px; }
.stat-click { cursor: pointer; transition: border-color .15s ease, box-shadow .15s ease; }
.stat-click:hover { border-color: color-mix(in srgb, var(--tech-primary) 45%, transparent); box-shadow: 0 2px 12px rgba(0, 0, 0, .12); }
.stat-foot { display: flex; justify-content: space-between; align-items: center; margin-top: 8px; padding-top: 6px; border-top: 1px dashed var(--tech-panel-border); font-size: 11.5px; }
.stat-foot .link { color: var(--tech-primary); }
.stat-click:hover .link { text-decoration: underline; }
.foot-right { display: inline-flex; align-items: center; gap: 8px; }
.lt-name { font-size: 12.5px; }
.suggest { color: var(--el-color-success); font-family: monospace; }
</style>
