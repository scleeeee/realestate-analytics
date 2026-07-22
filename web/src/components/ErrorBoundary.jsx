import { Component } from 'react';

export class ErrorBoundary extends Component {
  state = { hasError: false };

  static getDerivedStateFromError() {
    return { hasError: true };
  }

  componentDidCatch(error, info) {
    console.error('Unhandled error in component tree', error, info);
  }

  render() {
    if (this.state.hasError) {
      return <p role="alert">문제가 발생했습니다. 페이지를 새로고침해 주세요.</p>;
    }
    return this.props.children;
  }
}
