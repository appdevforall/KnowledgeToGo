/// <reference types="node" />
// The role rule. SchoolRoleTest.java checks the same cases in the app.
import test from 'node:test';
import assert from 'node:assert/strict';
import fs from 'fs';
import os from 'os';
import path from 'path';
import { parseRole, readRole } from './role';

test('no file is no role', () => {
    assert.equal(parseRole(null), 'none');
});

test('the teacher word', () => {
    assert.equal(parseRole('teacher'), 'teacher');
    assert.equal(parseRole(' Teacher\n'), 'teacher');
});

test('the student word', () => {
    assert.equal(parseRole('student\n'), 'student');
});

test('any other text is a student', () => {
    assert.equal(parseRole(''), 'student');
    assert.equal(parseRole('none'), 'student');
    assert.equal(parseRole('teacherx'), 'student');
});

test('the role file on disk', () => {
    const dir = fs.mkdtempSync(path.join(os.tmpdir(), 'k2go-role-'));
    try {
        const file = path.join(dir, 'role');
        assert.equal(readRole(file), 'none');
        assert.equal(readRole(path.join(dir, 'no-folder', 'role')), 'none');
        fs.writeFileSync(file, 'teacher\n');
        assert.equal(readRole(file), 'teacher');
        fs.writeFileSync(file, 'student\n');
        assert.equal(readRole(file), 'student');
        fs.writeFileSync(file, '');
        assert.equal(readRole(file), 'student');
        fs.rmSync(file);
        fs.mkdirSync(file);   // a folder in place of the file
        assert.equal(readRole(file), 'student');
    } finally {
        fs.rmSync(dir, { recursive: true, force: true });
    }
});
