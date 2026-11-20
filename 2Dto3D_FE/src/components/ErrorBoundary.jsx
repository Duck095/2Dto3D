import { Component } from 'react';
import PopArtSystemErrorPage from './error-pages/PopArtSystemErrorPage.jsx';

export default class ErrorBoundary extends Component {
    constructor(props) {
        super(props);
        this.state = { hasError: false };
    }

    static getDerivedStateFromError() {
        return { hasError: true };
    }

    componentDidCatch(error, errorInfo) {
        console.error('[ErrorBoundary]', error, errorInfo);
    }

    render() {
        if (this.state.hasError) {
            return (
                <PopArtSystemErrorPage
                    onRetry={() => this.setState({ hasError: false })}
                    nativeNavigation
                />
            );
        }

        return this.props.children;
    }
}
