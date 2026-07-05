import http from 'k6/http'
import { check } from 'k6'
import { buildBaseUrl } from './config.js'

export function loginSession() {
  const managementBase = buildBaseUrl('management')
  const username = __ENV.LOGIN_USERNAME || 'test'
  const password = __ENV.LOGIN_PASSWORD || 'test'
  const payload = JSON.stringify({ username, password })
  const res = http.post(`${managementBase}/admin/user/login`, payload, {
    headers: {
      'Content-Type': 'application/json',
      'X-Requested-With': 'XMLHttpRequest'
    },
    redirects: 0
  })
  check(res, {
    'login status is 200': r => r.status === 200,
    'login response code is 200': r => {
      try {
        return JSON.parse(r.body).code === 200
      } catch (e) {
        return false
      }
    }
  })
  const jar = http.cookieJar()
  const cookieHeader = jar.cookiesForURL(managementBase)
    .map(item => `${item.name}=${item.value}`)
    .join('; ')
  return {
    managementBase,
    businessBase: buildBaseUrl('business'),
    cookieHeader
  }
}
