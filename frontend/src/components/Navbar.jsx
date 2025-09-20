import React from 'react';
import {Link} from "react-router-dom";
import {useSelector} from "react-redux";
import PropTypes from "prop-types";

Navbar.propTypes = {
    focus: PropTypes.string
};

export default function Navbar({focus}) {
    const isAuthenticated = useSelector(state => state.auth.isAuthenticated);
    const pageSelected = useSelector(state => state.nav.pageSelected);

    return (
        <div>
            <nav className="navbar navbar-expand border-bottom border-body">
                <div className="container-fluid">
                    <Link className="navbar-brand me-1" to="/event/list"> <img
                        src="https://cdn.frankerfacez.com/emoticon/728261/animated/4" alt="Logo"
                        width="60"
                        height="40"
                        className="d-inline-block align-text-top"/>Peep Bot</Link>
                    <button className="navbar-toggler" type="button" data-bs-toggle="collapse"
                            data-bs-target="#navbarNavDropdown" aria-controls="navbarNavDropdown" aria-expanded="false"
                            aria-label="Toggle navigation">
                        <span className="navbar-toggler-icon"></span>
                    </button>
                    <div className="collapse navbar-collapse" id="navbarNavDropdown">
                        {isAuthenticated ? (

                            <ul className="navbar-nav">
                                <li className="nav-item">
                                    <Link className={"nav-link " + (focus === "LIST" ? "active" : "")}
                                          aria-current="page" to="/event/list">List</Link>
                                </li>
                                <li className="nav-item">
                                    <Link className={"nav-link " + (focus === "CREATE" ? "active" : "")}
                                          to="/event/create">Create</Link>
                                </li>
                            </ul>
                        ) : (<div/>)}
                    </div>
                </div>
            </nav>
        </div>
    )
}
//
//
//
//
