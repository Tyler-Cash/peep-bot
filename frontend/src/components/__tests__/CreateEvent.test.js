import React from 'react';
import {render, screen, fireEvent, waitFor} from '@testing-library/react';

// Mock the Navbar to avoid Redux store requirements
jest.mock('../Navbar', () => () => <div data-testid="navbar"/>);

// Mock the RTK Query hook to capture payloads
const mockCreateEvent = jest.fn(() => ({
  unwrap: jest.fn().mockRejectedValue(new Error('fail')) // prevent navigation
}));

jest.mock('../../api/eventBotApi', () => ({
  useCreateEventMutation: () => [mockCreateEvent]
}));

import CreateEvent from '../CreateEvent';

function fillAndSubmit(container, {name = 'My Event', description = 'desc', dateTime = '2030-12-01T10:00'}) {
  // Name
  fireEvent.change(screen.getByPlaceholderText('Enter event name'), {target: {value: name}});
  // Description
  fireEvent.change(screen.getByPlaceholderText('Describe your event'), {target: {value: description}});
  // DateTime (select by input type since label-for linkage lacks id)
  const dateInput = container.querySelector('input[type="datetime-local"]');
  fireEvent.change(dateInput, {target: {value: dateTime}});
  // Submit
  fireEvent.click(screen.getByRole('button', {name: /save changes/i}));
}

describe('CreateEvent form', () => {
  beforeEach(() => {
    mockCreateEvent.mockClear();
  });

  test('submits with notifyOnCreate defaulting to true when checkbox left checked', async () => {
    const {container} = render(<CreateEvent/>);

    // Checkbox should be checked by default
    const checkbox = screen.getByLabelText('Notify people now');
    expect(checkbox).toBeChecked();

    fillAndSubmit(container, {});

    await waitFor(() => expect(mockCreateEvent).toHaveBeenCalled());
    const payload = mockCreateEvent.mock.calls[0][0];
    expect(payload.name).toBe('My Event');
    // notifyOnCreate should be true by default
    expect(payload.notifyOnCreate).toBe(true);
  });

  test('submits with notifyOnCreate=false when checkbox is unchecked', async () => {
    const {container} = render(<CreateEvent/>);

    const checkbox = screen.getByLabelText('Notify people now');
    fireEvent.click(checkbox); // uncheck
    expect(checkbox).not.toBeChecked();

    fillAndSubmit(container, {});

    await waitFor(() => expect(mockCreateEvent).toHaveBeenCalled());
    const payload = mockCreateEvent.mock.calls[0][0];
    expect(payload.notifyOnCreate).toBe(false);
  });
});
