import React, { useState, useEffect, useRef } from 'react';

const App = () => {
  const [history, setHistory] = useState([]);
  const [messages, setMessages] = useState([]);
  const [question, setQuestion] = useState('');
  const [loading, setLoading] = useState(false);
  const [collapsed, setCollapsed] = useState(false);
  const sessionIdRef = useRef(null);
  const [toast, setToast] = useState(null);

  const showToast = (message) => {
    setToast(message);
    setTimeout(() => setToast(null), 3000);
  };

  const loadHistory = async () => {
    try {
      const items = await window.electronAPI.getHistory();
      setHistory(items || []);
    } catch (error) {
      showToast('历史记录加载失败');
    }
  };
  
  const handleReply = (event) => {
    const chunk = event.detail;
    setMessages(prev => {
      if (prev.length === 0) return prev;
      const updated = [...prev];
      const last = { ...updated[updated.length - 1] };
      last.answer = `${last.answer}${chunk.delta || ''}`;
      updated[updated.length - 1] = last;
      return updated;
    });
  };

  const handleError = (event) => {
    const message = event.detail?.message || '请求失败';
    showToast(message);
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
    if (!question.trim() || loading) return;
    try {
      const sessionId = Date.now().toString();
      sessionIdRef.current = sessionId;
      setLoading(true);
      setMessages(prev => [...prev, { id: sessionId, question, answer: '' }]);
      setQuestion('');
      const response = await window.electronAPI.ask(question, sessionId);
      setLoading(false);
      if (!response?.done) {
        showToast(response?.message || '请求失败');
      }
      await loadHistory();
    } catch (error) {
      setLoading(false);
      showToast('请求失败');
    }
  };

  const clearHistory = async () => {
    try {
      await window.electronAPI.clearHistory();
      setHistory([]);
    } catch (error) {
      showToast('清除失败');
    }
  };

  return (
    <div className="app">
      <aside className={`sidebar ${collapsed ? 'collapsed' : ''}`}>
        <div className="sidebar-header">
          <h2>历史记录</h2>
          <div className="sidebar-actions">
            <button onClick={() => setCollapsed(!collapsed)}>{collapsed ? '展开' : '折叠'}</button>
            <button onClick={clearHistory}>清空</button>
          </div>
        </div>
        {!collapsed && (
          <ul className="history-list">
            {history.map(item => (
              <li key={item.id}>
                <strong>{item.question}</strong>
                <span>{new Date(item.ts).toLocaleString()}</span>
              </li>
            ))}
            {history.length === 0 && <li>暂无记录</li>}
          </ul>
        )}
      </aside>

      <main className="chat-area">
        <header className="chat-header">
          <h1>AI 助手</h1>
        </header>
        <section className="chat-messages">
          {messages.map(item => (
            <div key={item.id} className="message">
              <div className="question">
                <span>你：</span>
                <p>{item.question}</p>
              </div>
              <div className="answer">
                <span>AI：</span>
                {item.answer ? (
                  <div>{item.answer}</div>
                ) : (
                  <p>AI 正在思考...</p>
                )}
              </div>
            </div>
          ))}
          {messages.length === 0 && <div>开始你的第一条提问吧。</div>}
        </section>
        <footer className="chat-input">
          <textarea
            rows="3"
            value={question}
            placeholder="输入你的问题..."
            onChange={e => setQuestion(e.target.value)}
          />
          <button onClick={sendQuestion} disabled={loading}>
            {loading ? '发送中...' : '发送'}
          </button>
        </footer>
      </main>

      {toast && <div className="toast">{toast}</div>}
    </div>
  );
};

export default App;