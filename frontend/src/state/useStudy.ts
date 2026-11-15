import { useSyncExternalStore } from "react";
import { studyStore, type StudyState } from "./store";

export function useStudy(): StudyState {
  return useSyncExternalStore(studyStore.subscribe, studyStore.getState);
}
