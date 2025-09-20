import {configureStore} from '@reduxjs/toolkit'

import rootReducer from './reducers/rootReducer'
import {eventBotApi} from './api/eventBotApi'

export default function configureAppStore(preloadedState) {
    const store = configureStore({
        reducer: rootReducer,
        middleware: (getDefaultMiddleware) =>
            getDefaultMiddleware().concat(eventBotApi.middleware),
        preloadedState,
        enhancers: (getDefaultEnhancers) => getDefaultEnhancers(),
    })

    if (import.meta.env.DEV && import.meta.hot) {
        import.meta.hot.accept('./reducers/rootReducer', (mod) => {
            const nextReducer = mod?.default ?? mod?.rootReducer ?? rootReducer
            store.replaceReducer(nextReducer)
        })
    }

    return store
}
