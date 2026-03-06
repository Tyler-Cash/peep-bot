import React from 'react';
import './css/events.css';

export default class ErrorBoundary extends React.Component {
    constructor(props) {
        super(props);
        this.state = { hasError: false };
    }

    static getDerivedStateFromError() {
        return { hasError: true };
    }

    componentDidCatch(error, errorInfo) {
        console.error('ErrorBoundary caught:', error, errorInfo);
    }

    render() {
        if (this.state.hasError) {
            return (
                <div className="container event-container">
                    <div className="event-card">
                        <div className="event-card-header">
                            <h2 className="mb-0">Something went wrong</h2>
                        </div>
                        <div className="event-card-body">
                            <p className="text-muted">An unexpected error occurred. Please try refreshing the page.</p>
                            <button
                                className="btn-event"
                                onClick={() => {
                                    this.setState({ hasError: false });
                                    window.location.href = '/';
                                }}
                            >
                                Go Home
                            </button>
                        </div>
                    </div>
                </div>
            );
        }

        return this.props.children;
    }
}
