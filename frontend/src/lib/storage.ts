/**
 * 浏览器侧持久化层。
 *
 * <p>Session ID 仍在 localStorage（恢复会话用）；账号 / token / 当前用户统一走 {@code loadAuth/saveAuth/clearAuth}。
 * 「常驻智能体」「LLM 网关预设」不再放这里 —— 已迁移到后端 MySQL（{@code /api/agents/persistent}、{@code /api/llm-presets}），
 * 本文件不再导出相关的 load/save/upsert/delete 工具。
 */

const KEY = 'mad.sessionId'
const AUTH_KEY = 'mad.auth'
const CLIENT_ID_KEY = 'mad.clientId'

import type { User } from '../api/types'

export interface AuthState {
  token: string
  user: User
}

export function loadSessionId(): string | null {
  try {
    return localStorage.getItem(KEY)
  } catch {
    return null
  }
}

export function saveSessionId(id: string) {
  try {
    localStorage.setItem(KEY, id)
  } catch {
    /* ignore */
  }
}

export function clearSessionId() {
  try {
    localStorage.removeItem(KEY)
  } catch {
    /* ignore */
  }
}

// ── 调试开关（控制内部标识是否露在界面上） ──────────────────
// 目前只管顶栏那个「会话 ID 角标」：对用户没意义，默认藏起来。

const DEBUG_KEY = 'mad.debug'

/**
 * 是否显示内部调试信息。
 *
 * <p>开关方式：地址栏加 `?debug=1` 打开、`?debug=0` 关闭，两者都会写进 localStorage
 * 记住（本机自用更省事，不用每次改 URL）；没带参数时读上次的选择，默认关闭。
 */
export function isDebugMode(): boolean {
  try {
    const q = new URLSearchParams(window.location.search).get('debug')
    if (q === '1') {
      localStorage.setItem(DEBUG_KEY, '1')
      return true
    }
    if (q === '0') {
      localStorage.removeItem(DEBUG_KEY)
      return false
    }
    return localStorage.getItem(DEBUG_KEY) === '1'
  } catch {
    return false
  }
}

// ── 登录态（来自后端 /api/auth） ────────────────────────

export function loadAuth(): AuthState | null {
  try {
    const raw = localStorage.getItem(AUTH_KEY)
    if (!raw) return null
    const obj = JSON.parse(raw) as AuthState
    if (!obj || !obj.token || !obj.user) return null
    return obj
  } catch {
    return null
  }
}

export function saveAuth(state: AuthState) {
  try {
    localStorage.setItem(AUTH_KEY, JSON.stringify(state))
  } catch {
    /* ignore */
  }
}

export function clearAuth() {
  try {
    localStorage.removeItem(AUTH_KEY)
  } catch {
    /* ignore */
  }
}

// ── 浏览器实例标识（匿名会话归属用） ──────────────────────

/**
 * 首次访问时生成并记住的客户端标识，随后每个请求都以 `X-Client-Id` 发出。
 *
 * <p>后端用它把「匿名会话」归属到具体浏览器：不带 token 时，只有同一标识才看得到 /
 * 打得开自己创建的会话，避免同一内网里不同人的匿名会话互相可见。
 *
 * <p>它不是登录凭证，清掉 localStorage 就等于换了一个匿名身份（此前的匿名会话不再可见
 * ——但那时 `mad.sessionId` 也一并丢了，本来也恢复不了）。localStorage 不可用时返回空串，
 * 此时不发该头，后端行为与从前完全一致。
 */
export function loadClientId(): string {
  try {
    let id = localStorage.getItem(CLIENT_ID_KEY)
    if (!id) {
      id =
        typeof crypto !== 'undefined' && typeof crypto.randomUUID === 'function'
          ? crypto.randomUUID()
          : `c-${Date.now().toString(36)}-${Math.random().toString(36).slice(2, 10)}`
      localStorage.setItem(CLIENT_ID_KEY, id)
    }
    return id
  } catch {
    return ''
  }
}

// ── 上下文总结已随「继续讨论」模式一并移除 ──────────────────
