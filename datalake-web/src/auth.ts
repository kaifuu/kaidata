// 鉴权状态（令牌 + 用户 + 菜单），持久化到 localStorage。
// P0：本地令牌闸门；后端 AuthFilter 已强制校验。

import type { MenuRow } from './api'

const TOKEN_KEY = 'pharma_token'
const USER_KEY = 'pharma_user'
const MENU_KEY = 'pharma_menus'

export interface UserInfo { username: string; name: string; role: string }

export const auth = {
  token(): string | null { return localStorage.getItem(TOKEN_KEY) },
  user(): UserInfo | null {
    const raw = localStorage.getItem(USER_KEY)
    return raw ? (JSON.parse(raw) as UserInfo) : null
  },
  menus(): MenuRow[] {
    const raw = localStorage.getItem(MENU_KEY)
    return raw ? (JSON.parse(raw) as MenuRow[]) : []
  },
  set(token: string, user: UserInfo, menus: MenuRow[]) {
    localStorage.setItem(TOKEN_KEY, token)
    localStorage.setItem(USER_KEY, JSON.stringify(user))
    localStorage.setItem(MENU_KEY, JSON.stringify(menus))
  },
  clear() {
    localStorage.removeItem(TOKEN_KEY)
    localStorage.removeItem(USER_KEY)
    localStorage.removeItem(MENU_KEY)
  },
  isAuthed(): boolean { return !!localStorage.getItem(TOKEN_KEY) }
}
