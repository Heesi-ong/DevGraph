import { useEffect, useRef } from 'react'
import * as monaco from 'monaco-editor'
import EditorWorker from 'monaco-editor/editor/editor.worker.js?worker'
import JsonWorker from 'monaco-editor/language/json/json.worker.js?worker'
import CssWorker from 'monaco-editor/language/css/css.worker.js?worker'
import HtmlWorker from 'monaco-editor/language/html/html.worker.js?worker'
import TsWorker from 'monaco-editor/language/typescript/ts.worker.js?worker'
import { toMonacoLanguage } from './languages'

// Monaco는 web worker를 스스로 만들지 못한다. 번들러가 만든 worker를 label별로 연결한다.
// 이 모듈은 Snippet Editor route에서만 dynamic import된다(설계서 §16.4).
self.MonacoEnvironment = {
  getWorker(_workerId: string, label: string) {
    if (label === 'json') return new JsonWorker()
    if (label === 'css' || label === 'scss' || label === 'less') return new CssWorker()
    if (label === 'html' || label === 'handlebars' || label === 'razor') return new HtmlWorker()
    if (label === 'typescript' || label === 'javascript') return new TsWorker()
    return new EditorWorker()
  },
}

interface Props {
  value: string
  language: string
  onChange: (value: string) => void
  ariaLabel: string
}

// 저장된 코드는 그대로여야 하므로 입력을 바꾸는 편의 기능(자동 닫기·자동 들여쓰기·붙여넣기 서식)을 끈다.
// 참고: Monaco 모델은 줄바꿈을 하나의 EOL로 통일한다. 줄바꿈이 섞인 코드를 편집하면 다수 쪽으로 맞춰진다.
export default function CodeEditor({ value, language, onChange, ariaLabel }: Props) {
  const container = useRef<HTMLDivElement>(null)
  const editorRef = useRef<monaco.editor.IStandaloneCodeEditor | null>(null)
  const onChangeRef = useRef(onChange)
  onChangeRef.current = onChange

  useEffect(() => {
    if (!container.current) return
    const editor = monaco.editor.create(container.current, {
      value,
      language: toMonacoLanguage(language),
      ariaLabel,
      automaticLayout: true,
      minimap: { enabled: false },
      scrollBeyondLastLine: false,
      tabSize: 2,
      autoClosingBrackets: 'never',
      autoClosingQuotes: 'never',
      autoClosingOvertype: 'never',
      autoSurround: 'never',
      autoIndent: 'none',
      formatOnType: false,
      formatOnPaste: false,
      quickSuggestions: false,
      suggestOnTriggerCharacters: false,
      theme: window.matchMedia('(prefers-color-scheme: dark)').matches ? 'vs-dark' : 'vs',
    })
    editorRef.current = editor
    const subscription = editor.onDidChangeModelContent(() => {
      onChangeRef.current(editor.getValue())
    })
    return () => {
      subscription.dispose()
      editor.dispose()
      editorRef.current = null
    }
    // 에디터는 한 번만 만든다. 값/언어 변경은 아래 effect가 반영한다.
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [])

  useEffect(() => {
    const editor = editorRef.current
    const model = editor?.getModel()
    if (editor && model && editor.getValue() !== value) {
      editor.setValue(value)
    }
  }, [value])

  useEffect(() => {
    const model = editorRef.current?.getModel()
    if (model) monaco.editor.setModelLanguage(model, toMonacoLanguage(language))
  }, [language])

  return <div ref={container} className="code-editor" data-testid="code-editor" />
}
