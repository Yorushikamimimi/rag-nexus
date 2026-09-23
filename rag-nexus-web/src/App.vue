<script setup lang="ts">
import { ref, computed, onMounted, nextTick } from 'vue'
import axios from 'axios'
import MarkdownIt from 'markdown-it'
import { v4 as uuidv4 } from 'uuid'

// html:false 禁止渲染原始 HTML，防止 LLM 输出注入 <script>/恶意标签导致 XSS
const md = new MarkdownIt({ html: false })

interface ChatMessage {
  id: string
  role: 'user' | 'assistant'
  content: string
  loading?: boolean
}

interface KbDocStat {
  docName: string
  chunks: number
}

interface KbStatsData {
  totalChunks: number
  totalDocs: number
  documents: KbDocStat[]
}

interface KbChunkPreviewItem {
  text: string
  truncated: boolean
  characterCount: number
}

interface KbChunkPreviewData {
  docName: string
  found: boolean
  totalChunks: number
  displayedChunks: number
  maxChunks: number
  maxCharactersPerChunk: number
  truncated: boolean
  orderingNote: string
  chunks: KbChunkPreviewItem[]
}

const API_BASE = ''

const sessionId = ref<string>('')
const messages = ref<ChatMessage[]>([])
const inputText = ref('')
const messagesEnd = ref<HTMLElement | null>(null)

const kbPanelOpen = ref(false)
const kbStatsLoading = ref(false)
const kbStats = ref<KbStatsData | null>(null)
const kbStatsError = ref('')
const previewDocName = ref('')
const chunkPreviewLoading = ref(false)
const chunkPreviewError = ref('')
const chunkPreview = ref<KbChunkPreviewData | null>(null)
const previewCharacterCount = computed(() =>
  chunkPreview.value?.chunks.reduce((total, chunk) => total + Array.from(chunk.text).length, 0) ?? 0,
)
const hasTruncatedChunk = computed(() => chunkPreview.value?.chunks.some((chunk) => chunk.truncated) ?? false)

const fileInput = ref<HTMLInputElement | null>(null)
const uploadLoading = ref(false)
const isStreaming = ref(false)
const currentAbortController = ref<AbortController | null>(null)
let kbStatsRequestId = 0
let chunkPreviewRequestId = 0

function scrollToBottom(): void {
  nextTick(() => {
    messagesEnd.value?.scrollIntoView({ behavior: 'smooth' })
  })
}

onMounted(() => {
  sessionId.value = uuidv4()
  messages.value = [
    {
      id: uuidv4(),
      role: 'assistant',
      content: '你好，我是知识库助手。你可以先导入资料，再开始提问。',
    },
  ]
  scrollToBottom()
})

function handleFileUpload(event: Event): void {
  const target = event.target as HTMLInputElement
  const file = target.files?.[0]
  if (!file || uploadLoading.value) return

  uploadLoading.value = true
  const formData = new FormData()
  formData.append('file', file)

  axios
    .post<{ code: number; message: string }>(`${API_BASE}/api/v1/kb/upload`, formData)
    .then(async ({ data: res }) => {
      if (res.code === 200) {
        if (kbPanelOpen.value) {
          await fetchKbStats()
        }
        alert('文档切片已入库')
      } else {
        alert(res.message || '上传失败')
      }
    })
    .catch((err: unknown) => {
      const msg = axios.isAxiosError(err)
        ? err.response?.data?.message || err.message || '网络错误'
        : String(err)
      alert(`上传失败：${msg}`)
    })
    .finally(() => {
      uploadLoading.value = false
      target.value = ''
    })
}

async function fetchKbStats(): Promise<void> {
  const requestId = ++kbStatsRequestId
  kbStatsLoading.value = true
  kbStatsError.value = ''

  try {
    const { data: res } = await axios.get<{
      code: number
      message: string
      data: KbStatsData
    }>(`${API_BASE}/api/v1/kb/stats`)

    if (requestId !== kbStatsRequestId) return

    if (res.code === 200) {
      kbStats.value = res.data
    } else {
      kbStatsError.value = res.message || '获取知识库统计失败'
    }
  } catch (err: unknown) {
    if (requestId !== kbStatsRequestId) return

    const msg = axios.isAxiosError(err)
      ? err.response?.data?.message || err.message || '网络错误'
      : String(err)
    kbStatsError.value = `获取知识库统计失败：${msg}`
  } finally {
    if (requestId === kbStatsRequestId) {
      kbStatsLoading.value = false
    }
  }
}

async function showChunkPreview(docName: string): Promise<void> {
  const requestId = ++chunkPreviewRequestId
  previewDocName.value = docName
  chunkPreview.value = null
  chunkPreviewError.value = ''
  chunkPreviewLoading.value = true

  try {
    const { data: res } = await axios.get<{
      code: number
      message: string
      data: KbChunkPreviewData
    }>(`${API_BASE}/api/v1/kb/chunks`, { params: { docName } })

    if (requestId !== chunkPreviewRequestId || previewDocName.value !== docName) return

    if (res.code === 200) {
      chunkPreview.value = res.data
    } else {
      chunkPreviewError.value = res.message || '读取已入库文本预览失败'
    }
  } catch (err: unknown) {
    if (requestId !== chunkPreviewRequestId || previewDocName.value !== docName) return

    const msg = axios.isAxiosError(err)
      ? err.response?.data?.message || err.message || '网络错误'
      : String(err)
    chunkPreviewError.value = `读取预览失败：${msg}`
  } finally {
    if (requestId === chunkPreviewRequestId && previewDocName.value === docName) {
      chunkPreviewLoading.value = false
    }
  }
}

function closeChunkPreview(): void {
  chunkPreviewRequestId += 1
  previewDocName.value = ''
  chunkPreview.value = null
  chunkPreviewError.value = ''
  chunkPreviewLoading.value = false
}

async function toggleKbPanel(): Promise<void> {
  kbPanelOpen.value = !kbPanelOpen.value
  if (kbPanelOpen.value) {
    await fetchKbStats()
  }
}

function stopGeneration(): void {
  if (currentAbortController.value) {
    currentAbortController.value.abort()
  }
}

async function sendMessage(): Promise<void> {
  const query = inputText.value.trim()
  if (!query || isStreaming.value) return

  const userMsg: ChatMessage = {
    id: uuidv4(),
    role: 'user',
    content: query,
  }
  messages.value.push(userMsg)
  inputText.value = ''

  const placeholderMsg: ChatMessage = {
    id: uuidv4(),
    role: 'assistant',
    content: '',
  }
  messages.value.push(placeholderMsg)
  scrollToBottom()

  isStreaming.value = true
  const controller = new AbortController()
  currentAbortController.value = controller

  try {
    const response = await fetch(`${API_BASE}/api/v1/kb/chat/stream`, {
      method: 'POST',
      headers: { 'Content-Type': 'application/json' },
      signal: controller.signal,
      body: JSON.stringify({
        sessionId: sessionId.value,
        query,
        topK: 3,
        temperature: 0.3,
      }),
    })

    const idx = messages.value.findIndex((m) => m.id === placeholderMsg.id)
    if (idx === -1) return

    if (!response.ok) {
      const errText = await response.text()
      messages.value[idx] = {
        ...messages.value[idx],
        content: `请求失败 (${response.status}): ${errText || response.statusText}`,
      }
      scrollToBottom()
      return
    }

    const reader = response.body?.getReader()
    if (!reader) {
      messages.value[idx] = {
        ...messages.value[idx],
        content: '响应体不可读',
      }
      scrollToBottom()
      return
    }

    const decoder = new TextDecoder('utf-8')
    let accumulated = ''
    let buffer = ''

    while (true) {
      const { done, value } = await reader.read()
      if (done) break

      const text = decoder.decode(value, { stream: true })
      buffer += text.replace(/\r\n/g, '\n')

      const lines = buffer.split('\n')
      buffer = lines.pop() ?? ''

      for (const line of lines) {
        if (line.startsWith('data:')) {
          let chunkContent = line.replace(/^data:\s*/, '')
          if (chunkContent === '[DONE]' || chunkContent.trim() === '') continue
          chunkContent = chunkContent.replace(/\\n/g, '\n')
          accumulated += chunkContent
          messages.value[idx] = { ...messages.value[idx], content: accumulated }
          scrollToBottom()
        }
      }
    }

    if (buffer.startsWith('data:')) {
      let chunkContent = buffer.replace(/^data:\s*/, '')
      if (chunkContent !== '[DONE]' && chunkContent.trim() !== '') {
        chunkContent = chunkContent.replace(/\\n/g, '\n')
        accumulated += chunkContent
        messages.value[idx] = { ...messages.value[idx], content: accumulated }
      }
    }

    scrollToBottom()
  } catch (err: unknown) {
    const idx = messages.value.findIndex((m) => m.id === placeholderMsg.id)
    if (err instanceof DOMException && err.name === 'AbortError') {
      if (idx !== -1 && !messages.value[idx].content.trim()) {
        messages.value[idx] = {
          ...messages.value[idx],
          content: '已停止生成',
        }
      }
    } else if (idx !== -1) {
      messages.value[idx] = {
        ...messages.value[idx],
        content: `请求异常：${err instanceof Error ? err.message : String(err)}`,
      }
    }
    scrollToBottom()
  } finally {
    isStreaming.value = false
    currentAbortController.value = null
  }
}
</script>

<template>
  <div class="flex min-h-screen flex-col bg-slate-50 text-slate-800">
    <header class="shrink-0 border-b border-slate-200 bg-white">
      <div class="mx-auto flex w-full max-w-6xl flex-wrap items-center justify-between gap-3 px-4 py-4 sm:px-6">
        <div class="min-w-0">
          <h1 class="text-xl font-semibold tracking-tight text-slate-900">RAG Nexus</h1>
          <p class="mt-0.5 text-sm text-slate-500">文本知识库工作区</p>
        </div>
        <div class="flex w-full flex-wrap gap-2 sm:w-auto sm:justify-end">
          <input
            ref="fileInput"
            type="file"
            class="hidden"
            accept=".txt,.md,.docx,.pdf"
            @change="handleFileUpload"
          />
          <button
            type="button"
            :disabled="uploadLoading"
            class="min-h-10 flex-1 rounded-lg bg-indigo-600 px-4 py-2 text-sm font-semibold text-white shadow-sm transition hover:bg-indigo-700 focus-visible:outline-none focus-visible:ring-2 focus-visible:ring-indigo-500 focus-visible:ring-offset-2 disabled:cursor-not-allowed disabled:opacity-60 sm:flex-none"
            @click="fileInput?.click()"
          >
            {{ uploadLoading ? '解析中...' : '上传文档' }}
          </button>
          <button
            type="button"
            :aria-expanded="kbPanelOpen"
            class="min-h-10 flex-1 rounded-lg border border-slate-300 bg-white px-4 py-2 text-sm font-medium text-slate-700 transition hover:bg-slate-50 focus-visible:outline-none focus-visible:ring-2 focus-visible:ring-indigo-500 focus-visible:ring-offset-2 sm:flex-none"
            @click="toggleKbPanel()"
          >
            {{ kbPanelOpen ? '收起知识库' : '查看知识库' }}
          </button>
        </div>
      </div>

      <section
        v-if="kbPanelOpen"
        aria-labelledby="knowledge-base-heading"
        class="border-t border-slate-100 bg-slate-50 px-4 py-4 sm:px-6"
      >
        <div class="mx-auto max-w-6xl">
          <div class="mb-3 flex flex-wrap items-center justify-between gap-2">
            <div>
              <h2 id="knowledge-base-heading" class="font-semibold text-slate-900">知识库</h2>
              <p class="mt-0.5 text-sm text-slate-500">选择文档可查看向量库中已保存的文本切片。</p>
            </div>
            <button
              type="button"
              :disabled="kbStatsLoading"
              class="min-h-9 rounded-lg border border-slate-300 bg-white px-3 py-1.5 text-sm font-medium text-slate-700 hover:bg-slate-100 focus-visible:outline-none focus-visible:ring-2 focus-visible:ring-indigo-500 disabled:cursor-wait disabled:opacity-60"
              @click="fetchKbStats()"
            >
              {{ kbStatsLoading ? '刷新中...' : '刷新列表' }}
            </button>
          </div>

          <p v-if="kbStatsLoading && !kbStats" role="status" class="rounded-lg bg-white px-3 py-3 text-sm text-slate-600">
            正在读取知识库…
          </p>
          <p v-else-if="kbStatsError" role="alert" class="rounded-lg border border-rose-200 bg-rose-50 px-3 py-3 text-sm text-rose-800">
            {{ kbStatsError }}
          </p>
          <p v-else-if="kbStats && kbStats.documents.length === 0" role="status" class="rounded-lg border border-slate-200 bg-white px-3 py-4 text-sm text-slate-600">
            知识库暂无已入库切片。上传含可提取文本的 TXT、MD、DOCX 或 PDF 后，这里会显示文档。
          </p>
          <div v-else-if="kbStats" class="grid gap-4 lg:grid-cols-[minmax(0,0.9fr)_minmax(0,1.1fr)]">
            <section class="overflow-hidden rounded-xl border border-slate-200 bg-white shadow-sm">
              <div class="border-b border-slate-100 px-4 py-3 text-sm text-slate-600">
                共 {{ kbStats.totalDocs }} 份文档、{{ kbStats.totalChunks }} 个切片
              </div>
              <ul class="max-h-64 divide-y divide-slate-100 overflow-y-auto">
                <li v-for="doc in kbStats.documents" :key="doc.docName" class="flex flex-wrap items-center justify-between gap-3 px-4 py-3">
                  <div class="min-w-0 flex-1">
                    <p class="break-all text-sm font-medium text-slate-800">{{ doc.docName }}</p>
                    <p class="mt-0.5 text-xs text-slate-500">{{ doc.chunks }} 个已入库切片</p>
                  </div>
                  <button
                    type="button"
                    class="min-h-9 shrink-0 rounded-lg border border-indigo-200 bg-indigo-50 px-3 py-1.5 text-sm font-medium text-indigo-700 hover:bg-indigo-100 focus-visible:outline-none focus-visible:ring-2 focus-visible:ring-indigo-500"
                    @click="showChunkPreview(doc.docName)"
                  >
                    查看切片
                  </button>
                </li>
              </ul>
            </section>

            <section v-if="previewDocName" aria-live="polite" class="min-w-0 overflow-hidden rounded-xl border border-slate-200 bg-white shadow-sm">
              <div class="flex flex-wrap items-start justify-between gap-3 border-b border-slate-100 px-4 py-3">
                <div class="min-w-0">
                  <h3 class="font-semibold text-slate-900">切片内容</h3>
                  <p class="mt-1 break-all text-sm text-slate-600">{{ previewDocName }}</p>
                </div>
                <button
                  type="button"
                  aria-label="关闭文本预览"
                  class="min-h-9 rounded-lg px-3 text-sm font-medium text-slate-600 hover:bg-slate-100 focus-visible:outline-none focus-visible:ring-2 focus-visible:ring-indigo-500"
                  @click="closeChunkPreview()"
                >
                  关闭
                </button>
              </div>
              <div class="max-h-[55vh] space-y-3 overflow-y-auto p-4">
                <p v-if="chunkPreviewLoading" role="status" class="text-sm text-slate-600">正在读取切片…</p>
                <p v-else-if="chunkPreviewError" role="alert" class="rounded-lg bg-rose-50 p-3 text-sm text-rose-800">
                  {{ chunkPreviewError }}
                </p>
                <p v-else-if="chunkPreview && !chunkPreview.found" role="status" class="rounded-lg bg-amber-50 p-3 text-sm leading-6 text-amber-900">
                  没有找到这份文件的已入库切片。它可能已被移除，或没有可提取的文本；当前数据结构无法区分这两种情况。
                </p>
                <template v-else-if="chunkPreview">
                  <p class="text-sm text-slate-600">
                    共 {{ chunkPreview.totalChunks }} 个切片 · 当前显示 {{ chunkPreview.displayedChunks }} 个 · 预览 {{ previewCharacterCount }} 个字符
                  </p>
                  <p v-if="chunkPreview.truncated" role="status" class="text-sm text-amber-800">
                    预览已截断<span v-if="chunkPreview.totalChunks > chunkPreview.displayedChunks"> · 仅显示前 {{ chunkPreview.displayedChunks }} 片</span><span v-if="hasTruncatedChunk"> · 个别切片只显示前 {{ chunkPreview.maxCharactersPerChunk }} 个字符</span>
                  </p>
                  <article v-for="(chunk, index) in chunkPreview.chunks" :key="`${previewDocName}-${index}`" class="overflow-hidden rounded-lg border border-slate-200">
                    <h4 class="border-b border-slate-100 bg-slate-50 px-3 py-2 text-xs font-semibold uppercase tracking-wide text-slate-600">
                      切片 {{ index + 1 }} · {{ chunk.characterCount }} 个字符<span v-if="chunk.truncated"> · 已截断</span>
                    </h4>
                    <pre class="whitespace-pre-wrap break-words px-3 py-3 font-sans text-sm leading-6 text-slate-800">{{ chunk.text }}</pre>
                  </article>
                </template>
              </div>
            </section>
          </div>
        </div>
      </section>
    </header>

    <section class="mx-auto w-full max-w-5xl px-4 pb-3 pt-5 sm:px-6">
      <h2 class="text-lg font-semibold text-slate-900">知识库问答</h2>
      <p class="mt-1 text-sm text-slate-500">上传文本资料后提问；回答会根据已检索到的片段生成。</p>
    </section>

    <main class="flex min-h-[18rem] flex-1 flex-col overflow-y-auto px-4 pb-4 sm:px-6">
      <div class="mx-auto flex w-full max-w-5xl flex-1 flex-col gap-3">
        <div
          v-for="msg in messages"
          :key="msg.id"
          :class="[
            'max-w-[95%] rounded-2xl border px-4 py-3 shadow-sm sm:max-w-[88%]',
            msg.role === 'user'
              ? 'ml-auto border-indigo-100 bg-indigo-50 text-slate-900'
              : 'mr-auto border-slate-200 bg-white text-slate-800',
          ]"
        >
          <template v-if="msg.loading">
            <div class="flex items-center gap-2 text-sm text-slate-500">
              <span class="inline-block h-2 w-2 animate-pulse rounded-full bg-indigo-500"></span>
              <span class="inline-block h-2 w-2 animate-pulse rounded-full bg-indigo-400" style="animation-delay: 0.2s"></span>
              <span class="inline-block h-2 w-2 animate-pulse rounded-full bg-indigo-300" style="animation-delay: 0.4s"></span>
              <span class="ml-1">思考中…</span>
            </div>
          </template>
          <template v-else>
            <div
              v-if="msg.role === 'assistant'"
              class="prose prose-sm max-w-none break-words text-slate-800 prose-headings:text-slate-900 prose-a:text-indigo-700 prose-code:text-slate-800"
              v-html="md.render(msg.content)"
            />
            <p v-else class="whitespace-pre-wrap break-words text-sm leading-6">{{ msg.content }}</p>
          </template>
        </div>
        <div ref="messagesEnd" />
      </div>
    </main>

    <footer class="shrink-0 border-t border-slate-200 bg-white px-4 py-3 sm:px-6">
      <div class="mx-auto flex w-full max-w-5xl flex-col gap-2 sm:flex-row">
        <input
          v-model="inputText"
          type="text"
          placeholder="输入问题，开始 RAG 对话…"
          class="min-h-11 min-w-0 flex-1 rounded-lg border border-slate-300 bg-white px-4 py-2.5 text-slate-900 placeholder:text-slate-400 focus:border-indigo-500 focus:outline-none focus:ring-2 focus:ring-indigo-100"
          @keydown.enter.prevent="sendMessage()"
        />
        <div class="grid grid-cols-2 gap-2 sm:flex">
          <button
            type="button"
            :disabled="isStreaming"
            class="min-h-11 rounded-lg bg-indigo-600 px-4 py-2.5 font-semibold text-white transition hover:bg-indigo-700 focus-visible:outline-none focus-visible:ring-2 focus-visible:ring-indigo-500 focus-visible:ring-offset-2 disabled:cursor-not-allowed disabled:opacity-50"
            @click="sendMessage()"
          >
            发送
          </button>
          <button
            type="button"
            :disabled="!isStreaming"
            class="min-h-11 rounded-lg border border-slate-300 bg-white px-4 py-2.5 font-medium text-slate-700 transition hover:bg-slate-50 focus-visible:outline-none focus-visible:ring-2 focus-visible:ring-slate-400 focus-visible:ring-offset-2 disabled:cursor-not-allowed disabled:opacity-50"
            @click="stopGeneration()"
          >
            停止生成
          </button>
        </div>
      </div>
    </footer>
  </div>
</template>
