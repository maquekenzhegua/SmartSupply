import pluginVue from 'eslint-plugin-vue'
import { defineConfigWithVueTs, vueTsConfigs } from '@vue/eslint-config-typescript'
import globals from 'globals'

export default defineConfigWithVueTs(
  { ignores: ['dist/**', 'node_modules/**', '*.d.ts'] },
  pluginVue.configs['flat/recommended'],
  vueTsConfigs.recommended,
  {
    languageOptions: {
      globals: { ...globals.browser },
    },
    rules: {
      // 演示项目允许 console 调试输出进 lint，但禁止 alert
      'no-alert': 'error',
      'no-console': 'off',
      // 组件名：views 下的 index 类文件会误报，宽松处理
      'vue/multi-word-component-names': 'off',
      // Element Plus 组件多为多属性长标签，关闭强制换行
      'vue/max-attributes-per-line': 'off',
      'vue/singleline-html-element-content-newline': 'off',
      'vue/html-self-closing': ['error', { html: { void: 'always', normal: 'never', component: 'always' } }],
      '@typescript-eslint/no-explicit-any': 'warn',
      'no-unused-vars': 'off',
      '@typescript-eslint/no-unused-vars': ['error', { argsIgnorePattern: '^_' }],
    },
  },
)
