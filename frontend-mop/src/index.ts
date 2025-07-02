import { mount } from 'svelte';
import { writable } from 'svelte/store';
import App from './App.svelte';
import type { BrokkEvent } from './types';

// Declare global interfaces for Java bridge
declare global {
  interface Window {
    brokk: {
      onEvent: (payload: BrokkEvent) => void;
      _eventBuffer: BrokkEvent[];
    };
    javaBridge?: {
      onAck: (epoch: number) => void;
      jsLog: (level: string, message: string) => void;
      getSelection: () => string;
    };
  }
}

// Create a writable store for events
const eventStore = writable<BrokkEvent>({ type: '' } as BrokkEvent);

// Instantiate the app using Svelte 5 API
const app = mount(App, {
  target: document.body,
  props: { eventStore }
});

// Save any buffered events
const bufferedEvents = window.brokk._eventBuffer || [];

// Replace the temporary brokk event handler with the real one
  window.brokk = {
    onEvent: (payload) => {
      console.log('Received event from Java bridge:', JSON.stringify(payload));
      eventStore.set(payload);

      // ACK after a frame render to ensure UI has updated
      if (payload.epoch) {
        requestAnimationFrame(() => {
          if (window.javaBridge) {
            window.javaBridge.onAck(payload.epoch);
          }
        });
      }
    },
    getSelection: () => {
      return window.getSelection()?.toString() ?? '';
    }
  };

// Process any events that were buffered before initialization
if (bufferedEvents.length > 0) {
  bufferedEvents.forEach(event => {
    window.brokk.onEvent(event);
  });
}
