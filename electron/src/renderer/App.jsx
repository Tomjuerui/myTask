import React, { useEffect, useMemo, useRef, useState } from 'react';
import ReactMarkdown from 'react-markdown';
import hljs from 'highlight.js';

const useToast = () => {
  const [toast, setToast] = useState(null);

  const show = (message) => {
    setToast(message);
    setTimeout(() => setToast(null), 3000);
  };

  return { toast, show };
};

const formatTime = (ts) => new Date(ts).toLocaleString();

const App = () => {
  const [history, setHistory] = useState([]);
  const [messages, setMessages] = useState([]);
  const [question, setQuestion] = useState('');
  const [loading, setLoading] = useState(false);
  const [collapsed, setCollapsed] = useState(false);
  const sessionIdRef = useRef(null);
  const { toast, show } = useToast();

  const markdownComponents = useMemo(
    () => ({
      code({ inline, className, children, ...props }) {
        const match = /language-(\w+)/.exec(className || '');
        const language = match ? match[1] : 'plaintext';
        const codeText = String(children).replace(/\n$/, '');

        if (inline) {
          return (
            <code className="inline-code" {...props}>
              {children}
            </code>
          );
        }

        const highlighted = hljs.highlight(codeText, { language }).value;

        return (
          <pre className="code-block">
            <code dangerouslySetInnerHTML={{ __html: highlighted }} />
          </pre>
        );
      }
    }),
    []
  );

  const loadHistory = async () => {
    try {
      const items = await window.electronAPI.getHistory();
      setHistory(items);
    } catch (error) {
      show('历史记录加载失败');
    }
  };

  const handleReply = (event) => {
    const chunk = event.detail;
    setMessages((prev) => {
      if (prev.length === 0) {
        return prev;
      }
      const updated = [...prev];
      const last = { ...updated[updated.length - 1] };
      last.answer = `${last.answer}${chunk.delta || ''}`;
      updated[updated.length - 1] = last;
      return updated;
    });
  };

  const handleError = (event) => {
    const message = event.detail?.message || '请求失败';
    show(message);
    setLoading(false);
  };

  useEffect(() => {
    loadHistory();
    window.addEventListener('ai-reply', handleReply);
    window.addEventListener('error', handleError);
    return () => {
      window.removeEventListener('ai-reply', handleReply);
      window.removeEventListener('error', handleError);
    };
  }, []);

  const sendQuestion = async () => {
    if (!question.trim() || loading) {
      return;
    }
    try {
      const sessionId = Date.now().toString();
      sessionIdRef.current = sessionId;
      setLoading(true);
      setMessages((prev) => [
        ...prev,
        { id: sessionId, question, answer: '' }
      ]);
      setQuestion('');
      const response = await window.electronAPI.ask(question, sessionId);
      setLoading(false);
      if (!response?.done) {
        show(response?.message || '请求失败');
      }
      await loadHistory();
    } catch (error) {
      setLoading(false);
      show('请求失败');
    }
  };

  const clearHistory = async () => {
    try {
      await window.electronAPI.clearHistory();
      setHistory([]);
    } catch (error) {
      show('清除失败');
    }
  };

  return (
    <div className="app">
      <aside className={`sidebar ${collapsed ? 'collapsed' : ''}`}>
        <div className="sidebar-header">
          <h2>历史记录</h2>
          <div className="sidebar-actions">
            <button type="button" onClick={() => setCollapsed(!collapsed)}>
              {collapsed ? '展开' : '折叠'}
            </button>
            <button type="button" onClick={clearHistory}>
              清空
            </button>
          </div>
        </div>
        {!collapsed && (
          <ul className="history-list">
            {history.map((item) => (
              <li key={item.id}>
                <strong>{item.question}</strong>
                <span>{formatTime(item.ts)}</span>
              </li>
            ))}
            {history.length === 0 && <li className="empty">暂无记录</li>}
          </ul>
        )}
      </aside>

      <main className="chat-area">
        <header className="chat-header">
          <h1>AI 助手</h1>
        </header>
        <section className="chat-messages">
          {messages.map((item) => (
            <div key={item.id} className="message">
              <div className="question">
                <span>你：</span>
                <p>{item.question}</p>
              </div>
              <div className="answer">
                <span>AI：</span>
                {item.answer ? (
                  <ReactMarkdown components={markdownComponents}>{item.answer}</ReactMarkdown>
                ) : (
                  <p className="typing">AI 正在思考...</p>
                )}
              </div>
            </div>
          ))}
          {messages.length === 0 && <div className="empty">开始你的第一条提问吧。</div>}
        </section>
        <footer className="chat-input">
          <textarea
            rows="3"
            value={question}
            placeholder="输入你的问题..."
            onChange={(event) => setQuestion(event.target.value)}
          />
          <button type="button" onClick={sendQuestion} disabled={loading}>
            {loading ? '发送中...' : '发送'}
          </button>
        </footer>
      </main>

      {toast && <div className="toast">{toast}</div>}
    </div>
  );
};

export default App;
