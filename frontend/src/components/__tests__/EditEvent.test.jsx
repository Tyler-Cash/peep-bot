import React from 'react';
import {render, screen, fireEvent, waitFor} from '@testing-library/react';
import {MemoryRouter, Route, Routes} from 'react-router-dom';

vi.mock('../Navbar', () => ({default: () => <div data-testid="navbar"/>}));

vi.mock('react-hook-form', () => ({
    useForm: () => ({
        register: () => ({}),
        handleSubmit: () => (e) => e?.preventDefault?.(),
        setError: vi.fn(),
        setValue: vi.fn(),
        formState: {errors: {}, isSubmitting: false},
    }),
}));

const mockRemoveAttendee = vi.fn();
const mockPatchEvent = vi.fn();

vi.mock('../../api/eventBotApi', () => ({
    useGetEventQuery: vi.fn(),
    usePatchEventMutation: () => [mockPatchEvent],
    useRemoveAttendeeMutation: () => [mockRemoveAttendee],
}));

vi.mock('react-redux', async (importOriginal) => {
    const actual = await importOriginal();
    return {
        ...actual,
        useSelector: vi.fn(),
    };
});

import {useSelector} from 'react-redux';
import {useGetEventQuery} from '../../api/eventBotApi';
import EditEvent from '../EditEvent';

const mockEventData = {
    id: 'test-event-id',
    name: 'Test Event',
    description: 'A test event',
    capacity: 10,
    dateTime: '2030-12-01T10:00:00Z',
    accepted: [{snowflake: '111', name: 'Alice', instant: '2024-01-01T00:00:00Z'}],
    maybe: [{snowflake: '222', name: 'Bob', instant: '2024-01-02T00:00:00Z'}],
    declined: [{snowflake: '333', name: 'Carol', instant: '2024-01-03T00:00:00Z'}],
};

function renderEditEvent() {
    return render(
        <MemoryRouter initialEntries={['/event/test-event-id']}>
            <Routes>
                <Route path="/event/:id" element={<EditEvent/>}/>
            </Routes>
        </MemoryRouter>
    );
}

describe('EditEvent admin panel', () => {
    beforeEach(() => {
        mockRemoveAttendee.mockReset();
        mockRemoveAttendee.mockReturnValue({unwrap: vi.fn().mockResolvedValue({})});
        mockPatchEvent.mockReturnValue({unwrap: vi.fn().mockResolvedValue({})});
        useGetEventQuery.mockReturnValue({data: mockEventData, isFetching: false, error: null});
    });

    test('admin panel is visible when user is admin', () => {
        useSelector.mockImplementation(fn => fn({auth: {isAdmin: true}}));

        renderEditEvent();

        // The Attendees section heading and all attendee names should be visible
        expect(screen.getByText('Attendees')).toBeInTheDocument();
        expect(screen.getByText('Alice')).toBeInTheDocument();
        expect(screen.getByText('Bob')).toBeInTheDocument();
        expect(screen.getByText('Carol')).toBeInTheDocument();
    });

    test('admin panel is hidden when user is not admin', () => {
        useSelector.mockImplementation(fn => fn({auth: {isAdmin: false}}));

        renderEditEvent();

        // Neither the section heading nor any attendee names should be present
        expect(screen.queryByText('Attendees')).not.toBeInTheDocument();
        expect(screen.queryByText('Alice')).not.toBeInTheDocument();
        expect(screen.queryByText('Bob')).not.toBeInTheDocument();
        expect(screen.queryByText('Carol')).not.toBeInTheDocument();
    });

    test('all three attendee sections render one remove button each', () => {
        useSelector.mockImplementation(fn => fn({auth: {isAdmin: true}}));

        const {container} = renderEditEvent();

        // One remove button per attendee (3 attendees across accepted/maybe/declined)
        const removeButtons = container.querySelectorAll('.btn-outline-danger');
        expect(removeButtons).toHaveLength(3);
    });

    test('clicking remove button calls removeAttendee with attendee snowflake', async () => {
        useSelector.mockImplementation(fn => fn({auth: {isAdmin: true}}));

        renderEditEvent();

        // Find Alice's row and click her remove button
        const aliceRow = screen.getByText('Alice').closest('.d-flex');
        const removeButton = aliceRow.querySelector('.btn-outline-danger');
        fireEvent.click(removeButton);

        await waitFor(() => expect(mockRemoveAttendee).toHaveBeenCalledWith(
            expect.objectContaining({snowflake: '111', name: 'Alice'})
        ));
    });

    test('shows empty state message when attendee list is empty', () => {
        useSelector.mockImplementation(fn => fn({auth: {isAdmin: true}}));
        useGetEventQuery.mockReturnValue({
            data: {...mockEventData, accepted: [], maybe: [], declined: []},
            isFetching: false,
            error: null,
        });

        renderEditEvent();

        const noAttendeesMessages = screen.getAllByText('No attendees');
        expect(noAttendeesMessages).toHaveLength(3);
    });

    test('shows loading spinner while fetching', () => {
        useSelector.mockImplementation(fn => fn({auth: {isAdmin: true}}));
        useGetEventQuery.mockReturnValue({data: undefined, isFetching: true, error: null});

        renderEditEvent();

        expect(screen.getByText('Loading event information...')).toBeInTheDocument();
    });
});
