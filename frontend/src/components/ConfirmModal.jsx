import React from 'react';
import ReactDOM from 'react-dom';
import './css/events.css';

export default function ConfirmModal({show, title, message, confirmLabel, confirmColorClass, onConfirm, onCancel, isLoading}) {
    if (!show) return null;

    return ReactDOM.createPortal(
        <div className="confirm-modal-backdrop" onClick={onCancel}>
            <div className="confirm-modal" onClick={e => e.stopPropagation()}>
                <div className="confirm-modal-header">
                    <h5 className="mb-0">{title}</h5>
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
