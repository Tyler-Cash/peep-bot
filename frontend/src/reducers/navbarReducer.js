import {createSlice} from '@reduxjs/toolkit';

const initialState = {
    pageSelected: "EVENTS",
};

const navbarSlice = createSlice({
    name: 'navbar',
    initialState,
    reducers: {
        pageSelected(state, page) {
            state.pageSelected = page;
        },
    },
});

export const {pageSelected} = navbarSlice.actions;
export default navbarSlice.reducer;