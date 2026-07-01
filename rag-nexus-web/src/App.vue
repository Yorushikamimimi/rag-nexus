<script setup lang="ts">
import { ref, onMounted, nextTick } from 'vue'
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

const API_BASE = ''

const sessionId = ref<string>('')
const messages = ref<ChatMessage[]>([])
const inputText = ref('')
const messagesEnd = ref<HTMLElement | null>(null)

const ingestUrlInput = ref('')
const ingestLoading = ref(false)
const ingestSuccess = ref(false)
const kbPanelOpen = ref(false)
const kbStatsLoading = ref(false)
const kbStats = ref<KbStatsData | null>(null)

const fileInput = ref<HTMLInputElement | null>(null)
const uploadLoading = ref(false)
const isStreaming = ref(false)
const currentAbortController = ref<AbortController | null>(null)

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
    .then(({ data: res }) => {
      if (res.code === 200) {
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

async function ingestUrl(): Promise<void> {
  const url = ingestUrlInput.value.trim()
  if (!url || ingestLoading.value) return

  ingestLoading.value = true
  ingestSuccess.value = false

  try {
    const { data: res } = await axios.post<{
      code: number
      message: string
      data: { url: string; chunksCreated: number } | null
    }>(`${API_BASE}/api/v1/kb/ingest/url`, { url })

    if (res.code === 200) {
      ingestSuccess.value = true
      ingestUrlInput.value = ''
      setTimeout(() => {
        ingestSuccess.value = false
      }, 4000)
    } else {
      alert(res.message || '摄入失败')
    }
  } catch (err: unknown) {
    const msg = axios.isAxiosError(err)
      ? err.response?.data?.message || err.message || '网络错误'
      : String(err)
    alert(`摄入失败：${msg}`)
  } finally {
    ingestLoading.value = false
  }
}

async function fetchKbStats(): Promise<void> {
  if (kbStatsLoading.value) return
  kbStatsLoading.value = true

  try {
    const { data: res } = await axios.get<{
      code: number
      message: string
      data: KbStatsData
    }>(`${API_BASE}/api/v1/kb/stats`)

    if (res.code === 200) {
      kbStats.value = res.data
    } else {
      alert(res.message || '获取知识库统计失败')
    }
  } catch (err: unknown) {
    const msg = axios.isAxiosError(err)
      ? err.response?.data?.message || err.message || '网络错误'
      : String(err)
    alert(`获取知识库统计失败：${msg}`)
  } finally {
    kbStatsLoading.value = false
  }
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
  <div class="flex h-full flex-col bg-gray-900 text-gray-200">
    <header class="shrink-0 border-b border-gray-700 px-4 py-3">
      <div class="mx-auto flex max-w-4xl items-center justify-between gap-4">
        <h1 class="shrink-0 text-lg font-semibold tracking-wide">Yorushika 概念引擎</h1>
        <div class="flex min-w-0 max-w-md flex-1 items-center gap-2">
          <input
            v-model="ingestUrlInput"
            type="text"
            placeholder="输入 B 站 / YouTube 链接，导入知识库"
            :disabled="ingestLoading"
            class="flex-1 rounded-lg border border-gray-600 bg-gray-800 px-3 py-2 text-sm text-gray-200 placeholder-gray-500 focus:border-gray-500 focus:outline-none focus:ring-1 focus:ring-gray-500 disabled:cursor-not-allowed disabled:opacity-50"
            @keydown.enter.prevent="ingestUrl()"
          />
          <button
            type="button"
            :disabled="ingestLoading"
            class="shrink-0 rounded-lg border border-gray-600 bg-gray-700 px-3 py-2 text-sm font-medium text-gray-200 transition hover:bg-gray-600 focus:outline-none focus:ring-2 focus:ring-gray-500 focus:ring-offset-2 focus:ring-offset-gray-900 disabled:cursor-not-allowed disabled:opacity-50"
            @click="ingestUrl()"
          >
            {{ ingestLoading ? '抓取中...' : '导入 URL' }}
          </button>
          <input
            ref="fileInput"
            type="file"
            class="hidden"
            accept=".txt,.md,.pdf,.doc,.docx"
            @change="handleFileUpload"
          />
          <button
            type="button"
            :disabled="uploadLoading"
            class="shrink-0 rounded-lg border border-gray-600 bg-gray-700 px-3 py-2 text-sm font-medium text-gray-200 transition hover:bg-gray-600 focus:outline-none focus:ring-2 focus:ring-gray-500 focus:ring-offset-2 focus:ring-offset-gray-900 disabled:cursor-not-allowed disabled:opacity-50"
            @click="fileInput?.click()"
          >
            {{ uploadLoading ? '解析中...' : '上传文档' }}
          </button>
          <button
            type="button"
            class="shrink-0 rounded-lg border border-indigo-500 bg-indigo-600 px-3 py-2 text-sm font-medium text-white transition hover:bg-indigo-500 focus:outline-none focus:ring-2 focus:ring-indigo-400 focus:ring-offset-2 focus:ring-offset-gray-900"
            @click="toggleKbPanel()"
          >
            {{ kbPanelOpen ? '收起知识库' : '查看知识库' }}
          </button>
        </div>
      </div>

      <Transition name="fade">
        <p v-if="ingestSuccess" class="mt-2 text-center text-sm font-medium text-emerald-400">
          资料导入成功，知识库已更新。
        </p>
      </Transition>

      <div
        v-if="kbPanelOpen"
        class="mx-auto mt-3 max-w-4xl rounded-lg border border-gray-700 bg-gray-800/70 p-3"
      >
        <div class="mb-2 flex items-center justify-between">
          <h2 class="text-sm font-semibold text-gray-100">知识库状态</h2>
          <button
            type="button"
            :disabled="kbStatsLoading"
            class="rounded border border-gray-600 bg-gray-700 px-2 py-1 text-xs text-gray-200 hover:bg-gray-600 disabled:opacity-50"
            @click="fetchKbStats()"
          >
            {{ kbStatsLoading ? '刷新中...' : '刷新' }}
          </button>
        </div>

        <div v-if="kbStatsLoading && !kbStats" class="text-sm text-gray-400">加载中...</div>

        <div v-else-if="kbStats" class="space-y-2 text-sm text-gray-200">
          <p>总切片：{{ kbStats.totalChunks }}，文档数：{{ kbStats.totalDocs }}</p>
          <div class="max-h-40 overflow-y-auto rounded border border-gray-700">
            <table class="w-full text-left text-xs">
              <thead class="bg-gray-700/80 text-gray-300">
                <tr>
                  <th class="px-2 py-1">文档</th>
                  <th class="w-24 px-2 py-1">Chunks</th>
                </tr>
              </thead>
              <tbody>
                <tr
                  v-for="doc in kbStats.documents"
                  :key="doc.docName"
                  class="border-t border-gray-700"
                >
                  <td class="px-2 py-1">{{ doc.docName }}</td>
                  <td class="px-2 py-1">{{ doc.chunks }}</td>
                </tr>
              </tbody>
            </table>
          </div>
        </div>
      </div>
    </header>

    <main class="flex-1 overflow-y-auto px-4 py-4">
      <div class="mx-auto max-w-2xl space-y-4">
        <div
          v-for="msg in messages"
          :key="msg.id"
          :class="[
            'rounded-lg px-4 py-3',
            msg.role === 'user' ? 'ml-auto max-w-[85%] bg-gray-700' : 'mr-auto max-w-[90%] bg-gray-800',
          ]"
        >
          <template v-if="msg.loading">
            <div class="flex items-center gap-2 text-gray-400">
              <span class="inline-block h-2 w-2 animate-pulse rounded-full bg-gray-400"></span>
              <span class="inline-block h-2 w-2 animate-pulse rounded-full bg-gray-400" style="animation-delay: 0.2s"></span>
              <span class="inline-block h-2 w-2 animate-pulse rounded-full bg-gray-400" style="animation-delay: 0.4s"></span>
              <span class="ml-1 text-sm">思考中...</span>
            </div>
          </template>
          <template v-else>
            <div
              v-if="msg.role === 'assistant'"
              class="prose prose-invert prose-sm max-w-none break-words"
              v-html="md.render(msg.content)"
            />
            <p v-else class="whitespace-pre-wrap break-words">{{ msg.content }}</p>
          </template>
        </div>
        <div ref="messagesEnd" />
      </div>
    </main>

    <footer class="shrink-0 border-t border-gray-700 px-4 py-3">
      <div class="mx-auto flex max-w-2xl gap-2">
        <input
          v-model="inputText"
          type="text"
          placeholder="输入问题，开始 RAG 对话..."
          class="flex-1 rounded-lg border border-gray-600 bg-gray-800 px-4 py-2.5 text-gray-200 placeholder-gray-500 focus:border-gray-500 focus:outline-none focus:ring-1 focus:ring-gray-500"
          @keydown.enter.prevent="sendMessage()"
        />
        <button
          type="button"
          :disabled="isStreaming"
          class="rounded-lg bg-gray-700 px-4 py-2.5 font-medium text-gray-200 transition hover:bg-gray-600 focus:outline-none focus:ring-2 focus:ring-gray-500 focus:ring-offset-2 focus:ring-offset-gray-900"
          @click="sendMessage()"
        >
          发送
        </button>
        <button
          type="button"
          :disabled="!isStreaming"
          class="rounded-lg bg-red-700 px-4 py-2.5 font-medium text-gray-100 transition hover:bg-red-600 focus:outline-none focus:ring-2 focus:ring-red-500 focus:ring-offset-2 focus:ring-offset-gray-900 disabled:opacity-40"
          @click="stopGeneration()"
        >
          停止生成
        </button>
      </div>
    </footer>
  </div>
</template>

<style scoped>
.fade-enter-active,
.fade-leave-active {
  transition: opacity 0.3s ease;
}

.fade-enter-from,
.fade-leave-to {
  opacity: 0;
}
</style>