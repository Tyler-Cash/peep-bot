import React, {useEffect, useRef, useCallback} from 'react';
import ReactDOM from 'react-dom';
import './css/events.css';

export default function ConfirmModal({show, title, message, confirmLabel, confirmColorClass, onConfirm, onCancel, isLoading}) {
    const modalRef = useRef(null);
    const previousFocusRef = useRef(null);

    const handleKeyDown = useCallback((e) => {
        if (e.key === 'Escape' && !isLoading) {
            onCancel();
            return;
        }
        if (e.key === 'Tab' && modalRef.current) {
            const focusable = modalRef.current.querySelectorAll('button:not([disabled])');
            if (focusable.length === 0) return;
            const first = focusable[0];
            const last = focusable[focusable.length - 1];
            if (e.shiftKey && document.activeElement === first) {
                e.preventDefault();
                last.focus();
            } else if (!e.shiftKey && document.activeElement === last) {
                e.preventDefault();
                first.focus();
            }
        }
    }, [isLoading, onCancel]);

    useEffect(() => {
        if (show) {
            previousFocusRef.current = document.activeElement;
            document.addEventListener('keydown', handleKeyDown);
            setTimeout(() => {
                const firstButton = modalRef.current?.querySelector('button:not([disabled])');
                firstButton?.focus();
            }, 0);
        }
        return () => {
            document.removeEventListener('keydown', handleKeyDown);
            if (previousFocusRef.current) {
                previousFocusRef.current.focus();
            }
        };
    }, [show, handleKeyDown]);

    if (!show) return null;

    return ReactDOM.createPortal(
        <div className="confirm-modal-backdrop" onClick={onCancel} role="presentation">
            <div
                className="confirm-modal"
                onClick={e => e.stopPropagation()}
                ref={modalRef}
                role="dialog"
                aria-modal="true"
                aria-labelledby="confirm-modal-title"
            >
                <div className="confirm-modal-header">
                    <h5 className="mb-0" id="confirm-modal-title">{title}</h5>
                </div>
                <div className="confirm-modal-body">
                    <p className="mb-0">{message}</p>
                </div>
                <div className="confirm-modal-footer">
                    <button type="button" className="btn-confirm-cancel" onClick={onCancel} disabled={isLoading}>
                        Cancel
                    </button>
                    <button type="button" className={confirmColorClass || 'btn-confirm-danger'} onClick={onConfirm} disabled={isLoading}>
                        {isLoading ? (
                            <span>
                                <span className="spinner-border spinner-border-sm me-2" aria-hidden="true"/>
                                Processing...
                            </span>
                        ) : confirmLabel}
                    </button>
                </div>
            </div>
        </div>,
        document.body
    );
}
