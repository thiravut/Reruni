import { useEffect, useRef } from 'react';
import type { WsMessage } from '../types/api';

interface Options {
  enabled?: boolean;
  onMessage: (msg: WsMessage) => void;
  onOpen?: () => void;
  onClose?: () => void;
}

/**
 * Connect to /ws/portal with auto-reconnect exponential backoff (1s..30s).
 * Replies to server pings with a pong message.
 */
export function useWebSocket({
  enabled = true,
  onMessage,
  onOpen,
  onClose,
}: Options) {
  const onMessageRef = useRef(onMessage);
  const onOpenRef = useRef(onOpen);
  const onCloseRef = useRef(onClose);

  useEffect(() => {
    onMessageRef.current = onMessage;
    onOpenRef.current = onOpen;
    onCloseRef.current = onClose;
  }, [onMessage, onOpen, onClose]);

  useEffect(() => {
    if (!enabled) return;

    let cancelled = false;
    let ws: WebSocket | null = null;
    let backoff = 1000;
    let reconnectTimer: number | null = null;

    function scheduleReconnect() {
      if (cancelled) return;
      if (reconnectTimer !== null) return;
      reconnectTimer = window.setTimeout(() => {
        reconnectTimer = null;
        connect();
      }, backoff);
      backoff = Math.min(backoff * 2, 30000);
    }

    function connect() {
      if (cancelled) return;
      const apiBase = import.meta.env.VITE_API_BASE_URL ?? '';
      let url: string;
      if (apiBase) {
        // https://api.reruni.com → wss://api.reruni.com/ws/portal
        url = apiBase.replace(/^http/, 'ws') + '/ws/portal';
      } else {
        // Dev: vite proxy forwards /ws/* to the Go backend.
        const proto = location.protocol === 'https:' ? 'wss' : 'ws';
        url = `${proto}://${location.host}/ws/portal`;
      }
      try {
        ws = new WebSocket(url);
      } catch {
        scheduleReconnect();
        return;
      }

      ws.onopen = () => {
        backoff = 1000;
        onOpenRef.current?.();
      };

      ws.onmessage = (e) => {
        let msg: WsMessage;
        try {
          msg = JSON.parse(e.data) as WsMessage;
        } catch {
          return;
        }
        if (!msg) return;
        // Auto-respond to pings.
        if (msg.type === 'ping' && ws && ws.readyState === WebSocket.OPEN) {
          ws.send(
            JSON.stringify({
              id: cryptoId(),
              type: 'pong',
              payload: {},
              timestamp: new Date().toISOString(),
            }),
          );
          return;
        }
        onMessageRef.current(msg);
      };

      ws.onclose = () => {
        onCloseRef.current?.();
        if (!cancelled) scheduleReconnect();
      };

      ws.onerror = () => {
        try {
          ws?.close();
        } catch {
          /* noop */
        }
      };
    }

    connect();

    return () => {
      cancelled = true;
      if (reconnectTimer !== null) {
        clearTimeout(reconnectTimer);
        reconnectTimer = null;
      }
      if (ws) {
        ws.onopen = null;
        ws.onmessage = null;
        ws.onclose = null;
        ws.onerror = null;
        try {
          ws.close();
        } catch {
          /* noop */
        }
        ws = null;
      }
    };
  }, [enabled]);
}

function cryptoId(): string {
  if (typeof crypto !== 'undefined' && 'randomUUID' in crypto) {
    return crypto.randomUUID();
  }
  return Math.random().toString(36).slice(2);
}
