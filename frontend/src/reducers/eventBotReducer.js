import {createSlice} from '@reduxjs/toolkit';
import {eventBotApi} from "../api/eventBotApi";

const initialState = {
    isAuthenticated: false,
};

const eventBotSlice = createSlice({
    name: 'eventBot',
    initialState,
    reducers: {
        [eventBotApi.reducerPath]: eventBotApi.reducer,
    },
    middleware: (getDefaultMiddleware) =>
        getDefaultMiddleware().concat(eventBotApi.middleware),
});

export const {loginSuccess, logout} = eventBotSlice.actions;
export default eventBotSlice.reducer;