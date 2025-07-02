<script>
  import Markdown from 'svelte-exmarkdown';
  import remarkBreaks from 'remark-breaks';
  import { gfmPlugin } from 'svelte-exmarkdown/gfm';
  import { onDestroy } from 'svelte';

  export let eventStore;

  let markdown = '';
  let spinnerMessage = '';
  let isDarkTheme = false;

  // Subscribe to store changes explicitly to handle every event
  const unsubscribe = eventStore.subscribe(event => {
    switch (event.type) {
      case 'chunk':
        if (event.text) {
          markdown += event.text;
        }
        break;
      case 'theme':
        isDarkTheme = event.dark;
        break;
      case 'clear':
        markdown = '';
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
  .content {
    white-space: pre-wrap;
    word-break: break-all;
  }
  #spinner {
    padding: 0.5em;
    color: #888;
    display: none;
  }
  body.theme-dark #spinner {
    color: #888;
  }
</style>

<div class="content" class:theme-dark={isDarkTheme}>
  <Markdown md={markdown} plugins={[gfmPlugin(), remarkBreaks()]} />
</div>
<div id="spinner" style:display={spinnerMessage ? 'block' : 'none'}>{spinnerMessage}</div>
