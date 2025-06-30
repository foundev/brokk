<script>
  export let event = { type: '' };

  let logContent = '';
  let spinnerMessage = '';
  let isDarkTheme = false;

  $: {
    switch (event.type) {
      case 'chunk':
        logContent += JSON.stringify(event, null, 2) + '\n';
        break;
      case 'theme':
        isDarkTheme = event.dark;
        break;
      case 'clear':
        logContent = '';
        break;
      case 'spinner':
        spinnerMessage = event.message;
        break;
    }
  }
</script>

<style>
  body.theme-dark {
    background-color: #2b2b2b;
    color: #bbb;
  }
  pre {
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

<h1>Brokk MOP with Svelte</h1>
<p>Events from Java will be logged to the console and displayed raw below.</p>
<pre id="log">{logContent}</pre>
<div id="spinner" style:display={spinnerMessage ? 'block' : 'none'}>{spinnerMessage}</div>
