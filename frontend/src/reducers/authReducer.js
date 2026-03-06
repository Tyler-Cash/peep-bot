import {createSlice} from '@reduxjs/toolkit';

const initialState = {
    isAuthenticated: false,
    isAdmin: false,
    username: null,
    discordId: null,
};

const authSlice = createSlice({
    name: 'auth',
    initialState,
    reducers: {
        loginSuccess(state, action) {
            state.isAuthenticated = true;
            state.isAdmin = action.payload?.admin ?? false;
            state.username = action.payload?.username ?? null;
            state.discordId = action.payload?.discordId ?? null;
        },
        logout(state) {
            state.isAuthenticated = false;
            state.isAdmin = false;
            state.username = null;
            state.discordId = null;
        },
    },
});

export const {loginSuccess, logout} = authSlice.actions;
export default authSlice.reducer;
