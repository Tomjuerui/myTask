import React from 'react';
import { createRoot } from 'react-dom/client';
import App from './App.jsx';
import './styles.css';

console.log('React应用开始加载...');

const container = document.getElementById('root');
console.log('找到root容器:', container);

const root = createRoot(container);
console.log('创建Root实例:', root);

root.render(<App />);
console.log('React应用渲染完成');

// 监听全局错误
window.addEventListener('error', (error) => {
    console.error('全局错误:', error);
});

// 监听未捕获的Promise错误
window.addEventListener('unhandledrejection', (event) => {
    console.error('未捕获的Promise错误:', event.reason);
});
