import { apiClient } from '../../shared/api/client'

export interface SessionSummary {
  id: string
  current: boolean
  deviceLabel: string | null
  ipPrefix: string | null
  createdAt: string
  lastRotatedAt: string | null
  absoluteExpiresAt: string
}

export type ReauthPurpose = 'EXPORT_CREATE' | 'ACCOUNT_DELETE_REQUEST' | 'NODE_PERMANENT_DELETE' | 'IMPORT_CREATE'

export async function listSessions() {
  const { data } = await apiClient.get<{ items: SessionSummary[] }>('/auth/sessions')
  return data.items
}

export async function revokeSession(id: string) {
  await apiClient.delete(`/auth/sessions/${id}`)
}

export async function changePassword(currentPassword: string, newPassword: string, revokeOtherSessions: boolean) {
  await apiClient.patch('/auth/password', { currentPassword, newPassword, revokeOtherSessions })
}

export async function requestReauth(password: string, purpose: ReauthPurpose, targetId?: string) {
  const { data } = await apiClient.post<{ reauthToken: string; expiresAt: string }>('/auth/reauth', { password, purpose, targetId })
  return data.reauthToken
}

export async function requestDeletion(confirmation: string, reauthToken: string) {
  const { data } = await apiClient.post<{ scheduledAt: string }>('/account/deletion-request', { confirmation }, {
    headers: { 'X-Reauth-Token': reauthToken },
  })
  return data.scheduledAt
}

export async function cancelDeletion() {
  await apiClient.post('/account/deletion-cancel')
}
