const logger = {
    error: (message, ...args) => {
        console.error(`[PeepBot] ${message}`, ...args);
    },
    warn: (message, ...args) => {
        console.warn(`[PeepBot] ${message}`, ...args);
    },
    info: (message, ...args) => {
        if (import.meta.env.DEV) {
            console.log(`[PeepBot] ${message}`, ...args);
        }
    },
};

export default logger;
