// 사용자가 고르는 언어 id(서버는 소문자 [a-z0-9+#._-]만 받는다)를 각 라이브러리의 언어 id로 옮긴다.
export const COMMON_LANGUAGES = [
  'java', 'kotlin', 'typescript', 'javascript', 'python', 'go', 'rust', 'sql', 'bash',
  'yaml', 'json', 'html', 'css', 'c', 'cpp', 'csharp', 'text',
]

const MONACO_ALIASES: Record<string, string> = {
  bash: 'shell', sh: 'shell', zsh: 'shell', 'c++': 'cpp', 'c#': 'csharp', ts: 'typescript', js: 'javascript',
  py: 'python', text: 'plaintext', txt: 'plaintext', yml: 'yaml', kt: 'kotlin',
}

export function toMonacoLanguage(language: string): string {
  return MONACO_ALIASES[language] ?? language
}
