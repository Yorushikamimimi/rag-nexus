<script setup lang="ts">
import { ref, onMounted, nextTick } from 'vue'
import axios from 'axios'
import MarkdownIt from 'markdown-it'
import { v4 as uuidv4 } from 'uuid'

const md = new MarkdownIt({ html: true })

interface ChatMessage {
  id: string
  role: 'user' | 'assistant'
  content: string
  loading?: boolean
}

const sessionId = ref<string>('')
const messages = ref<ChatMessage[]>([])
const inputText = ref('')
const messagesEnd = ref<HTMLElement | null>(null)

// 知识库 URL 摄入
const ingestUrlInput = ref('')
const ingestLoading = ref(false)
const ingestSuccess = ref(false)

// 文档上传
const fileInput = ref<HTMLInputElement | null>(null)
const uploadLoading = ref(false)

function handleFileUpload(event: Event): void {
  const target = event.target as HTMLInputElement
  const file = target.files?.[0]
  if (!file || uploadLoading.value) return

  uploadLoading.value = true
  const formData = new FormData()
  formData.append('file', file)

  axios
    .post<{ code: number; message: string; data?: unknown }>(
      `${API_BASE}/api/v1/kb/upload`,
      formData
    )
    .then(({ data: res }) => {
      if (res.code === 200) {
        alert('✅ 文档切片已入库！')
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
      setTimeout(() => { ingestSuccess.value = false }, 4000)
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

const scrollToBottom = () => {
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
      content: '你好，我是基于 Spring AI 的概念引擎。你想深入了解《盗作》的动机，还是《幻灯》的隐喻？',
    },
  ]
  scrollToBottom()
})

const API_BASE = ''

async function sendMessage(): Promise<void> {
  const query = inputText.value.trim()
  if (!query) return

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

  try {
    const response = await fetch(`${API_BASE}/api/v1/kb/chat/stream`, {
      method: 'POST',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify({
        sessionId: sessionId.value,
        query,
        topK: 3,
        temperature: 0.3,
      }),
    })

    let idx = messages.value.findIndex((m) => m.id === placeholderMsg.id)
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
    if (idx !== -1) {
      messages.value[idx] = {
        ...messages.value[idx],
        content: `请求异常：${err instanceof Error ? err.message : String(err)}`,
      }
    }
    scrollToBottom()
  }
}
</script>

<template>
  <div class="flex h-full flex-col bg-gray-900 text-gray-200">
    <!-- Header -->
    <header class="shrink-0 border-b border-gray-700 px-4 py-3">
      <div class="mx-auto flex max-w-4xl items-center justify-between gap-4">
        <h1 class="shrink-0 text-lg font-semibold tracking-wide">Yorushika 概念引擎</h1>
        <!-- 知识库自动投喂 -->
        <div class="flex min-w-0 flex-1 max-w-md items-center gap-2">
          <input
            v-model="ingestUrlInput"
            type="text"
            placeholder="输入 B站/YouTube 链接，给概念引擎喂饭..."
            :disabled="ingestLoading"
            class="flex-1 rounded-lg border border-gray-600 bg-gray-800 px-3 py-2 text-sm text-gray-200 placeholder-gray-500 focus:border-gray-500 focus:outline-none focus:ring-1 focus:ring-gray-500 disabled:opacity-50 disabled:cursor-not-allowed"
            @keydown.enter.prevent="ingestUrl()"
          />
          <button
            type="button"
            :disabled="ingestLoading"
            class="shrink-0 rounded-lg border border-gray-600 bg-gray-700 px-3 py-2 text-sm font-medium text-gray-200 transition hover:bg-gray-600 focus:outline-none focus:ring-2 focus:ring-gray-500 focus:ring-offset-2 focus:ring-offset-gray-900 disabled:opacity-50 disabled:cursor-not-allowed disabled:hover:bg-gray-700"
            @click="ingestUrl()"
          >
            {{ ingestLoading ? 'Loading... (抓取中)' : '一键摄入 (URL)' }}
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
            class="shrink-0 rounded-lg border border-gray-600 bg-gray-700 px-3 py-2 text-sm font-medium text-gray-200 transition hover:bg-gray-600 focus:outline-none focus:ring-2 focus:ring-gray-500 focus:ring-offset-2 focus:ring-offset-gray-900 disabled:opacity-50 disabled:cursor-not-allowed disabled:hover:bg-gray-700"
            @click="fileInput?.click()"
          >
            {{ uploadLoading ? '解析中...' : '📎 上传文档' }}
          </button>
        </div>
      </div>
      <!-- 摄入成功提示 -->
      <Transition name="fade">
        <p
          v-if="ingestSuccess"
          class="mt-2 text-center text-sm font-medium text-emerald-400"
        >
          ✅ 语料摄入成功，引擎已进化！
        </p>
      </Transition>
    </header>

    <!-- Messages -->
    <main class="flex-1 overflow-y-auto px-4 py-4">
      <div class="mx-auto max-w-2xl space-y-4">
        <div
          v-for="msg in messages"
          :key="msg.id"
          :class="[
            'rounded-lg px-4 py-3',
            msg.role === 'user'
              ? 'ml-auto max-w-[85%] bg-gray-700'
              : 'mr-auto max-w-[90%] bg-gray-800',
          ]"
        >
          <template v-if="msg.loading">
            <div class="flex items-center gap-2 text-gray-400">
              <span
                class="inline-block h-2 w-2 animate-pulse rounded-full bg-gray-400"
                style="animation-duration: 1.2s"
              ></span>
              <span
                class="inline-block h-2 w-2 animate-pulse rounded-full bg-gray-400"
                style="animation-delay: 0.2s; animation-duration: 1.2s"
              ></span>
              <span
                class="inline-block h-2 w-2 animate-pulse rounded-full bg-gray-400"
                style="animation-delay: 0.4s; animation-duration: 1.2s"
              ></span>
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

    <!-- Input -->
    <footer class="shrink-0 border-t border-gray-700 px-4 py-3">
      <div class="mx-auto flex max-w-2xl gap-2">
        <input
          v-model="inputText"
          type="text"
          placeholder="输入问题，连接 Spring AI 知识库..."
          class="flex-1 rounded-lg border border-gray-600 bg-gray-800 px-4 py-2.5 text-gray-200 placeholder-gray-500 focus:border-gray-500 focus:outline-none focus:ring-1 focus:ring-gray-500"
          @keydown.enter.prevent="sendMessage()"
        />
        <button
          type="button"
          class="rounded-lg bg-gray-700 px-4 py-2.5 font-medium text-gray-200 transition hover:bg-gray-600 focus:outline-none focus:ring-2 focus:ring-gray-500 focus:ring-offset-2 focus:ring-offset-gray-900"
          @click="sendMessage()"
        >
          发送
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
