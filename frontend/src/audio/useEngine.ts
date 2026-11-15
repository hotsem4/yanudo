import { useSyncExternalStore } from "react";
import { prefs } from "../lib/prefs";
import { AudioEngine, type EngineSnapshot } from "./engine";

export const audioEngine = new AudioEngine({ mode: prefs.get("mode"), rate: prefs.get("rate") });

export function useEngine(): EngineSnapshot {
  return useSyncExternalStore(audioEngine.subscribe, audioEngine.getSnapshot);
}
