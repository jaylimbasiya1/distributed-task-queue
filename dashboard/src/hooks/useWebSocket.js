import { useEffect, useRef, useState, useCallback } from 'react'
import { Client } from '@stomp/stompjs'
import SockJS from 'sockjs-client'

const MIN_RECONNECT_DELAY = 1000
const MAX_RECONNECT_DELAY = 30000

export default function useWebSocket(tenantId, onJobUpdate, onDLQJob) {
  const [connected, setConnected] = useState(false)
  const [reconnectAttempt, setReconnectAttempt] = useState(0)
  const clientRef = useRef(null)
  const onJobUpdateRef = useRef(onJobUpdate)
  const onDLQJobRef = useRef(onDLQJob)
  const reconnectTimerRef = useRef(null)
  const reconnectAttemptRef = useRef(0)

  useEffect(() => { onJobUpdateRef.current = onJobUpdate }, [onJobUpdate])
  useEffect(() => { onDLQJobRef.current = onDLQJob }, [onDLQJob])

  const connect = useCallback(() => {
    if (clientRef.current?.active) return

    const client = new Client({
      // Route through nginx so WebSocket upgrade headers are handled correctly
      webSocketFactory: () => new SockJS('/ws'),
      // Disable built-in reconnect — we manage it ourselves with backoff
      reconnectDelay: 0,
      onConnect: () => {
        setConnected(true)
        reconnectAttemptRef.current = 0
        setReconnectAttempt(0)
        if (reconnectTimerRef.current) {
          clearTimeout(reconnectTimerRef.current)
          reconnectTimerRef.current = null
        }

        // Subscribe only to tenant-specific topic to avoid processing every job twice
        client.subscribe(`/topic/jobs/${tenantId}`, (message) => {
          try {
            const job = JSON.parse(message.body)
            // Guard: ignore messages that slipped through for wrong tenant
            if (job.tenantId && job.tenantId !== tenantId) return
            if (job.status === 'DLQ') {
              onDLQJobRef.current?.(job)
            }
            onJobUpdateRef.current(job)
          } catch (e) {
            console.error('Failed to parse job message:', e)
          }
        })
      },
      onDisconnect: () => {
        setConnected(false)
        scheduleReconnect()
      },
      onStompError: (frame) => {
        console.error('STOMP error:', frame)
        setConnected(false)
        scheduleReconnect()
      },
      onWebSocketError: (event) => {
        console.error('WebSocket error:', event)
        setConnected(false)
      },
      onWebSocketClose: () => {
        setConnected(false)
        scheduleReconnect()
      },
    })

    clientRef.current = client
    client.activate()
  }, [tenantId])

  const scheduleReconnect = useCallback(() => {
    if (reconnectTimerRef.current) return
    const attempt = reconnectAttemptRef.current + 1
    reconnectAttemptRef.current = attempt
    setReconnectAttempt(attempt)

    // Exponential backoff: 1s, 2s, 4s, 8s, ... capped at 30s
    const delay = Math.min(MIN_RECONNECT_DELAY * Math.pow(2, attempt - 1), MAX_RECONNECT_DELAY)
    console.info(`WebSocket reconnect attempt ${attempt} in ${delay}ms`)

    reconnectTimerRef.current = setTimeout(() => {
      reconnectTimerRef.current = null
      if (clientRef.current) {
        clientRef.current.deactivate().then(() => {
          clientRef.current = null
          connect()
        })
      } else {
        connect()
      }
    }, delay)
  }, [connect])

  useEffect(() => {
    if (!tenantId) return
    connect()
    return () => {
      if (reconnectTimerRef.current) clearTimeout(reconnectTimerRef.current)
      clientRef.current?.deactivate()
      clientRef.current = null
      setConnected(false)
    }
  }, [tenantId, connect])

  return { connected, reconnectAttempt }
}
