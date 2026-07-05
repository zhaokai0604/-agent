import ws from 'k6/ws'
import { Counter, Rate, Trend } from 'k6/metrics'
import { sleep } from 'k6'
import { loginSession } from './lib/auth.js'
import { buildBaseUrl, getAllowedOrigin, getNumber } from './lib/config.js'

export const wsConnectSuccess = new Rate('ws_connect_success')
export const wsDisconnectRate = new Rate('ws_disconnect_rate')
export const wsMessageInterval = new Trend('ws_message_interval_ms')
export const wsMessages = new Counter('ws_message_count')

const vus = Number(__ENV.VUS || getNumber('ws.connections', 50))
const durationSec = Number(__ENV.DURATION_SEC || getNumber('ws.duration.sec', 900))

export const options = {
  scenarios: {
    websocket_capacity: {
      executor: 'constant-vus',
      vus,
      duration: `${durationSec}s`
    }
  },
  thresholds: {
    ws_connect_success: ['rate>0.99'],
    ws_disconnect_rate: ['rate<0.05']
  }
}

function buildWsUrl() {
  const managementBase = buildBaseUrl('management')
  return managementBase.replace(/^http/, 'ws') + '/ws/performance'
}

export default function () {
  const auth = loginSession()
  const url = buildWsUrl()
  const params = {
    headers: {
      Cookie: auth.cookieHeader,
      Origin: getAllowedOrigin()
    }
  }

  let lastMessageAt = 0
  const res = ws.connect(url, params, socket => {
    socket.on('open', () => {
      wsConnectSuccess.add(true)
      socket.send('refresh')
    })

    socket.on('message', message => {
      const now = Date.now()
      if (lastMessageAt) {
        wsMessageInterval.add(now - lastMessageAt)
      }
      lastMessageAt = now
      wsMessages.add(1)
      try {
        const parsed = JSON.parse(message)
        if (parsed.channel !== 'performance') {
          socket.send('sync')
        }
      } catch (e) {
        socket.send('sync')
      }
    })

    socket.on('close', () => {
      wsDisconnectRate.add(false)
    })

    socket.on('error', () => {
      wsDisconnectRate.add(true)
    })

    socket.setTimeout(() => {
      socket.close()
    }, durationSec * 1000)
  })

  if (!res || res.status !== 101) {
    wsConnectSuccess.add(false)
    wsDisconnectRate.add(true)
  }
  sleep(1)
}
