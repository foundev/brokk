export interface ChunkEvent {
  type: 'chunk';
  text: string;
  isNew: boolean;
  msgType: 'USER' | 'AI' | 'SYSTEM';
  epoch: number;
}

export interface ThemeEvent {
  type: 'theme';
  dark: boolean;
}

export interface SpinnerEvent {
  type: 'spinner';
  message: string;
}

export interface ClearEvent {
  type: 'clear';
}

export type BrokkEvent = ChunkEvent | ThemeEvent | SpinnerEvent | ClearEvent;

export interface Bubble {
  id: number;
  type: 'USER' | 'AI' | 'SYSTEM';
  markdown: string;
}
