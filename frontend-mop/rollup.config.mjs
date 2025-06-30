import { resolve, dirname } from 'path';
import { fileURLToPath } from 'url';
import nodeResolve from '@rollup/plugin-node-resolve';
import commonjs from '@rollup/plugin-commonjs';

const __dirname = dirname(fileURLToPath(import.meta.url));

export default {
  input: resolve(__dirname, 'src', 'index.js'),
  output: {
    file: resolve(__dirname, '..', 'src', 'main', 'resources', 'mop-web', 'bundle.js'),
    format: 'iife',
    name: 'BrokkApp',
    sourcemap: true
  },
  plugins: [nodeResolve(), commonjs()]
};
