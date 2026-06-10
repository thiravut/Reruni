import {
  createContext,
  useCallback,
  useContext,
  useEffect,
  useMemo,
  useRef,
  useState,
} from 'react';
import type { ReactNode } from 'react';
import { useAuth } from './AuthContext';
import { useToast } from './ToastContext';
import { useWebSocket } from '../hooks/useWebSocket';
import type {
  DeviceStatus,
  WsCommandCompleted,
  WsDeviceStatusChanged,
  WsErrorNotice,
  WsLiveEnded,
  WsLiveStarted,
  WsMessage,
} from '../types/api';

interface DeviceStatusPatch {
  status: DeviceStatus;
  last_seen_at: string;
}

interface RealtimeContextValue {
  connected: boolean;
  deviceStatuses: Map<number, DeviceStatusPatch>;
  liveStartCount: number;
  liveEndCount: number;
  onDeviceStatus: (cb: (msg: WsDeviceStatusChanged) => void) => () => void;
  onLiveStarted: (cb: (msg: WsLiveStarted) => void) => () => void;
  onLiveEnded: (cb: (msg: WsLiveEnded) => void) => () => void;
  onCommandCompleted: (cb: (msg: WsCommandCompleted) => void) => () => void;
}

const RealtimeContext = createContext<RealtimeContextValue | null>(null);

type Listener<T> = (msg: T) => void;

export function RealtimeProvider({ children }: { children: ReactNode }) {
  const { state } = useAuth();
  const toast = useToast();
  const enabled = !!state.user;

  const [connected, setConnected] = useState(false);
  const [deviceStatuses, setDeviceStatuses] = useState<
    Map<number, DeviceStatusPatch>
  >(new Map());
  const [liveStartCount, setLiveStartCount] = useState(0);
  const [liveEndCount, setLiveEndCount] = useState(0);

  const deviceStatusListeners = useRef<Set<Listener<WsDeviceStatusChanged>>>(
    new Set(),
  );
  const liveStartedListeners = useRef<Set<Listener<WsLiveStarted>>>(new Set());
  const liveEndedListeners = useRef<Set<Listener<WsLiveEnded>>>(new Set());
  const commandCompletedListeners = useRef<Set<Listener<WsCommandCompleted>>>(
    new Set(),
  );

  const handleMessage = useCallback(
    (msg: WsMessage) => {
      switch (msg.type) {
        case 'device_status_changed': {
          const p = msg.payload as WsDeviceStatusChanged;
          setDeviceStatuses((prev) => {
            const next = new Map(prev);
            next.set(p.device_id, {
              status: p.status,
              last_seen_at: p.last_seen_at,
            });
            return next;
          });
          deviceStatusListeners.current.forEach((cb) => cb(p));
          break;
        }
        case 'live_started': {
          const p = msg.payload as WsLiveStarted;
          setLiveStartCount((n) => n + 1);
          liveStartedListeners.current.forEach((cb) => cb(p));
          break;
        }
        case 'live_ended': {
          const p = msg.payload as WsLiveEnded;
          setLiveEndCount((n) => n + 1);
          liveEndedListeners.current.forEach((cb) => cb(p));
          break;
        }
        case 'command_completed': {
          const p = msg.payload as WsCommandCompleted;
          // Page-level subscribers (e.g. ActiveLivesPage) surface this inline;
          // only fall back to a toast when no one is listening so generic errors
          // don't go silent on pages that don't care.
          if (commandCompletedListeners.current.size === 0) {
            if (p.status === 'error' && p.error) {
              toast.error(`คำสั่งล้มเหลว: ${p.error.message}`);
            }
          } else {
            commandCompletedListeners.current.forEach((cb) => cb(p));
          }
          break;
        }
        case 'error_notice': {
          const p = msg.payload as WsErrorNotice;
          toast.error(`อุปกรณ์ #${p.device_id}: ${p.message}`);
          break;
        }
        default:
          break;
      }
    },
    [toast],
  );

  useWebSocket({
    enabled,
    onMessage: handleMessage,
    onOpen: () => setConnected(true),
    onClose: () => setConnected(false),
  });

  // Reset state when user changes (logout/login)
  useEffect(() => {
    if (!state.user) {
      setDeviceStatuses(new Map());
      setLiveStartCount(0);
      setLiveEndCount(0);
      setConnected(false);
    }
  }, [state.user]);

  const onDeviceStatus = useCallback((cb: Listener<WsDeviceStatusChanged>) => {
    deviceStatusListeners.current.add(cb);
    return () => {
      deviceStatusListeners.current.delete(cb);
    };
  }, []);

  const onLiveStarted = useCallback((cb: Listener<WsLiveStarted>) => {
    liveStartedListeners.current.add(cb);
    return () => {
      liveStartedListeners.current.delete(cb);
    };
  }, []);

  const onLiveEnded = useCallback((cb: Listener<WsLiveEnded>) => {
    liveEndedListeners.current.add(cb);
    return () => {
      liveEndedListeners.current.delete(cb);
    };
  }, []);

  const onCommandCompleted = useCallback(
    (cb: Listener<WsCommandCompleted>) => {
      commandCompletedListeners.current.add(cb);
      return () => {
        commandCompletedListeners.current.delete(cb);
      };
    },
    [],
  );

  const value = useMemo<RealtimeContextValue>(
    () => ({
      connected,
      deviceStatuses,
      liveStartCount,
      liveEndCount,
      onDeviceStatus,
      onLiveStarted,
      onLiveEnded,
      onCommandCompleted,
    }),
    [
      connected,
      deviceStatuses,
      liveStartCount,
      liveEndCount,
      onDeviceStatus,
      onLiveStarted,
      onLiveEnded,
      onCommandCompleted,
    ],
  );

  return (
    <RealtimeContext.Provider value={value}>
      {children}
    </RealtimeContext.Provider>
  );
}

export function useRealtime(): RealtimeContextValue {
  const ctx = useContext(RealtimeContext);
  if (!ctx) throw new Error('useRealtime must be used inside <RealtimeProvider>');
  return ctx;
}
