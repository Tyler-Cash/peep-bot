import React from 'react';

const PROD_HOSTNAME = 'event.tylercash.dev';

function getEnvironment() {
    const hostname = window.location.hostname;
    if (hostname === PROD_HOSTNAME) return null;
    if (hostname === 'localhost' || hostname === '127.0.0.1') return { label: 'Local Development', variant: 'local' };
    if (hostname.includes('staging')) return { label: 'Staging', variant: 'staging' };
    return { label: 'Test Environment', variant: 'test' };
}

export default function EnvironmentBanner() {
    const env = getEnvironment();
    if (!env) return null;

    return (
        <div className={`env-banner env-banner--${env.variant}`} role="status">
            {env.label} — {window.location.hostname}
        </div>
    );
}
