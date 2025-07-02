<script lang="ts">
  import Markdown from 'svelte-exmarkdown';
  import remarkBreaks from 'remark-breaks';
  import { gfmPlugin } from 'svelte-exmarkdown/gfm';
  import { onDestroy } from 'svelte';
  import type { Writable } from 'svelte/store';
  import type { BrokkEvent, Bubble } from './types';

  export let eventStore: Writable<BrokkEvent>;

  let bubbles: Bubble[] = [];
  let nextId = 0;
  let spinnerMessage = '';
  let isDarkTheme = false;

  // Subscribe to store changes explicitly to handle every event
  const unsubscribe = eventStore.subscribe(event => {
    switch (event.type) {
      case 'chunk':
        if (event.text) {
          if (bubbles.length === 0 || event.isNew || event.msgType !== bubbles[bubbles.length - 1].type) {
            bubbles = [...bubbles, { id: nextId++, type: event.msgType, markdown: event.text }];
          } else {
            bubbles[bubbles.length - 1].markdown += event.text;
            bubbles = [...bubbles]; // Trigger reactivity
          }
        }
        break;
      case 'theme':
        isDarkTheme = event.dark;
        break;
      case 'clear':
        bubbles = [];
        nextId = 0;
        break;
      case 'spinner':
        spinnerMessage = event.message;
        break;
    }
  });

  // Unsubscribe when component is destroyed to prevent memory leaks
  onDestroy(unsubscribe);
</script>

<style>
  body.theme-dark {
    background-color: #2b2b2b;
    color: #bbb;
  }
  .chat-container {
    display: flex;
    flex-direction: column;
    gap: 1em;
    max-width: 100%;
    margin: 0 auto;
  }
  .bubble {
    padding: 0.8em 1.2em;
    border-radius: 0.8em;
    max-width: 80%;
    white-space: pre-wrap;
    word-break: break-word;
    display: inline-block;
  }
  .bubble.USER {
    background-color: #e3f2fd;
    color: #0d47a1;
    align-self: flex-start;
  }
  .bubble.AI {
    background-color: #f5f5f5;
    color: #1b5e20;
    align-self: flex-end;
    text-align: right;
  }
  .bubble.SYSTEM {
    background-color: #fff3e0;
    color: #e65100;
    align-self: center;
    text-align: center;
  }
  body.theme-dark .bubble.USER {
    background-color: #1e3a8a;
    color: #bbdefb;
  }
  body.theme-dark .bubble.AI {
    background-color: #2e7d32;
    color: #c8e6c9;
  }
  body.theme-dark .bubble.SYSTEM {
    background-color: #9a3412;
    color: #ffcc80;
  }
  #spinner {
    padding: 0.5em;
    color: #888;
    display: none;
    text-align: center;
  }
  body.theme-dark #spinner {
    color: #888;
  }
</style>

<div class="chat-container" class:theme-dark={isDarkTheme}>
  {#each bubbles as bubble (bubble.id)}
    <div class="bubble {bubble.type}">
      <Markdown md={bubble.markdown} plugins={[gfmPlugin(), remarkBreaks()]} />
    </div>
  {/each}
</div>
<div id="spinner" style:display={spinnerMessage ? 'block' : 'none'}>{spinnerMessage}</div>
